package com.adgendoc.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>GATE HTTP E2E — Phase H-HTTP (HTTP Vertical Slice E2E)</b> (exécuté
 * SÉPARÉMENT du gate hors-ligne et du gate PostgreSQL, serveur Spring Boot +
 * PostgreSQL 16 réels requis) :
 *
 * <pre>
 * mvn -o -f backend/pom.xml test -Dtest=HttpVerticalSliceIT
 * </pre>
 *
 * <p>Aucun MockMvc, aucun double de persistence : le client est
 * {@link java.net.http.HttpClient} du JDK, les appels voyagent en TCP réel
 * vers le serveur Spring Boot démarré hors JVM de test, et les vérifications
 * de persistance passent par du JDBC réel sur PostgreSQL.</p>
 *
 * <p>Configuration (variables d'environnement, exigées sauf valeur par
 * défaut) :</p>
 * <ul>
 *   <li>{@code E2E_BASE_URL} — défaut {@code http://127.0.0.1:18099} ;</li>
 *   <li>{@code E2E_JDBC_URL} — défaut
 *       {@code jdbc:postgresql://127.0.0.1:5460/adgendoc} ;</li>
 *   <li>{@code E2E_JDBC_USER} — défaut {@code adgendoc} ;</li>
 *   <li>{@code E2E_JDBC_PASSWORD} — obligatoire ;</li>
 *   <li>{@code E2E_STORAGE_ROOT} — stockage de test du serveur, obligatoire ;</li>
 *   <li>{@code E2E_TEMPLATE_FILE} — copie de template utilisée par le serveur,
 *       obligatoire ;</li>
 *   <li>{@code E2E_REFERENCE_TEMPLATE} — template versionné du dépôt,
 *       obligatoire.</li>
 * </ul>
 *
 * <p>Les scénarios négatifs sont ordonnés avant le scénario « checksum
 * altéré », lequel restaure la copie de template dans un {@code finally} :</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpVerticalSliceIT {

    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String MARKER = "NON APPROUVÉ POUR PRODUCTION";
    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";
    private static final String API = "/api/v1/requests";
    private static final int MAX_PAYLOAD_BYTES = 65536;

    private static final String BASE_URL = env("E2E_BASE_URL",
            "http://127.0.0.1:18099");
    private static final String JDBC_URL = env("E2E_JDBC_URL",
            "jdbc:postgresql://127.0.0.1:5460/adgendoc");
    private static final String JDBC_USER = env("E2E_JDBC_USER", "adgendoc");

    private static String jdbcPassword;
    private static Path storageRoot;
    private static Path templateCopy;
    private static Path referenceTemplate;

    private static HttpClient client;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> NEGATIVE_BODIES = new ArrayList<>();

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertThat(value).as("variable d'environnement %s absente", name)
                .isNotBlank();
        return value;
    }

    @BeforeAll
    static void configure() throws Exception {
        jdbcPassword = required("E2E_JDBC_PASSWORD");
        storageRoot = Path.of(required("E2E_STORAGE_ROOT")).toAbsolutePath().normalize();
        templateCopy = Path.of(required("E2E_TEMPLATE_FILE")).toAbsolutePath().normalize();
        referenceTemplate = Path.of(required("E2E_REFERENCE_TEMPLATE"))
                .toAbsolutePath().normalize();

        assertThat(Files.isDirectory(storageRoot))
                .as("racine de stockage de test absente : %s", storageRoot).isTrue();
        assertThat(Files.isRegularFile(templateCopy))
                .as("copie de template absente : %s", templateCopy).isTrue();
        assertThat(Files.isRegularFile(referenceTemplate))
                .as("template versionné absent : %s", referenceTemplate).isTrue();
        assertThat(templateCopy)
                .as("la copie altérée doit être distincte du template versionné du dépôt")
                .isNotEqualTo(referenceTemplate);
        Path repoRoot = referenceTemplate.getParent() == null ? null
                : referenceTemplate.getParent().getParent();
        assertThat(repoRoot)
                .as("racine du dépôt non déductible de %s", referenceTemplate).isNotNull();
        assertThat(templateCopy.startsWith(repoRoot))
                .as("la copie tamponnée doit impérativement être hors du dépôt : %s", templateCopy)
                .isFalse();
        assertThat(sha256Hex(Files.readAllBytes(templateCopy)))
                .as("la copie servie par le serveur doit être identique au template versionné")
                .isEqualTo(sha256Hex(Files.readAllBytes(referenceTemplate)));

        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    // ------------------------------------------------------------------
    // 0 — Périmètre restreint contrôlé (contrat API §1.3, architecture §12)
    // ------------------------------------------------------------------

    /**
     * Le serveur ne doit répondre QUE sur le loopback : aucune adresse IPv4
     * non-loopback de la machine ne doit ouvrir une connexion vers le port du
     * serveur. Preuve comportementale du « périmètre restreint » que le contrat
     * impose à l'itération 1 (AGENTS.md §13 : l'accès au document doit être
     * restreint). Sauté (et non vacu) si la machine n'a aucune interface
     * non-loopback.
     */
    @Test
    @Order(15)
    void service_answers_only_on_loopback() {
        int port = portOfBaseUrl();
        List<InetAddress> remoteAddresses = nonLoopbackIpv4Addresses();
        Assumptions.assumeFalse(remoteAddresses.isEmpty(),
                "aucune adresse IPv4 non-loopback sur cette machine : preuve impossible");
        for (InetAddress address : remoteAddresses) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), 1000);
                org.junit.jupiter.api.Assertions.fail(
                        "le serveur accepte une connexion non-loopback sur " + address.getHostAddress()
                                + ":" + port + " — périmètre restreint violé");
            } catch (IOException expected) {
                // Refus de connexion / expiration : conforme (aucune écoute hors loopback).
            }
        }
    }

    private static int portOfBaseUrl() {
        URI uri = URI.create(BASE_URL);
        return uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
    }

    private static List<InetAddress> nonLoopbackIpv4Addresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                Enumeration<InetAddress> attached = interfaces.nextElement().getInetAddresses();
                while (attached.hasMoreElements()) {
                    InetAddress address = attached.nextElement();
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        addresses.add(address);
                    }
                }
            }
        } catch (SocketException unavailable) {
            return List.of();
        }
        return addresses;
    }

    // ------------------------------------------------------------------
    // 1 — Scénario principal : POST → GET → generate → téléchargement DOCX
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    void main_vertical_slice_over_real_tcp_http() throws Exception {
        HttpResponse<byte[]> created = post(API, completeBody());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode createdJson = body(created);
        assertThat(createdJson.path("status").asText()).isEqualTo("VALIDATED");
        UUID requestId = UUID.fromString(createdJson.path("requestId").asText());

        HttpResponse<byte[]> state = get(API + "/" + requestId);
        assertThat(state.statusCode()).isEqualTo(200);
        JsonNode stateJson = body(state);
        assertThat(stateJson.path("status").asText()).isEqualTo("VALIDATED");
        assertThat(stateJson.path("documentType").asText()).isEqualTo(DOCUMENT_TYPE);
        assertThat(stateJson.has("missingFields"))
                .as("missingFields doit être présent dans le contrat E2").isTrue();
        assertThat(stateJson.path("missingFields").isEmpty()).isTrue();
        assertThat(stateJson.path("data").path("dateNaissance").asText())
                .isEqualTo("1985-05-12");

        HttpResponse<byte[]> generated = post(API + "/" + requestId + "/generate", null);
        assertThat(generated.statusCode()).isEqualTo(201);
        JsonNode generatedJson = body(generated);
        assertThat(generatedJson.path("status").asText()).isEqualTo("GENERATED");
        UUID documentId = UUID.fromString(generatedJson.path("documentId").asText());

        HttpResponse<byte[]> download = get(API + "/" + requestId + "/documents/" + documentId);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Type").orElse(""))
                .isEqualTo(DOCX_MIME);
        assertThat(download.headers().firstValue("Content-Disposition").orElse(""))
                .isEqualTo("attachment; filename=\"attestation-concordance-" + requestId
                        + ".docx\"");
        byte[] downloaded = download.body();
        assertThat(downloaded.length).isGreaterThan(0);

        // DOCX ouvrable par Apache POI, marque présente, aucun {{placeholder}}.
        String text = poiText(downloaded);
        assertThat(text).contains("Maria").contains("Gomes")
                .contains(requestId.toString())
                .contains("1985-05-12").contains("Bissau")
                .contains(MARKER);
        assertThat(text).doesNotContain("{{").doesNotContain("}}");

        // Structure de stockage réelle : {storage}/{requestId}/{documentId}.docx
        Path storedFile = storageRoot.resolve(requestId.toString())
                .resolve(documentId + ".docx");
        assertThat(Files.isRegularFile(storedFile))
                .as("fichier stocké absent : %s", storedFile).isTrue();
        byte[] storedBytes = Files.readAllBytes(storedFile);

        // sha256(http) == sha256(fichier) == sha256(enregistré en base)
        String storedSha = queryString(
                "SELECT sha256 FROM generated_document WHERE document_id = ?", documentId);
        assertThat(sha256Hex(downloaded)).isEqualTo(storedSha);
        assertThat(sha256Hex(storedBytes)).isEqualTo(storedSha);
        assertThat(sha256Hex(downloaded)).isEqualTo(sha256Hex(storedBytes));

        // Relations PostgreSQL : request ⇄ document ⇄ template, statut final.
        assertThat(queryLong("SELECT count(*) FROM generated_document"
                + " WHERE document_id = ? AND request_id = ?", documentId, requestId))
                .isEqualTo(1);
        assertThat(queryString("SELECT r.status FROM document_request r"
                        + " JOIN generated_document d ON d.request_id = r.request_id"
                        + " WHERE r.request_id = ? AND d.document_id = ?",
                requestId, documentId)).isEqualTo("GENERATED");
        assertThat(queryLong("SELECT count(*) FROM template_registry t"
                        + " JOIN document_request r ON r.document_type = t.code"
                        + " WHERE r.request_id = ? AND t.active = true",
                requestId)).isEqualTo(1);
        assertThat(queryString("SELECT t.checksum FROM template_registry t"
                        + " JOIN document_request r ON r.document_type = t.code"
                        + " WHERE r.request_id = ?", requestId))
                .isEqualTo(sha256Hex(Files.readAllBytes(referenceTemplate)));
        assertThat(queryLong("SELECT count(*) FROM generated_document"
                + " WHERE storage_path = ?", requestId + "/" + documentId + ".docx"))
                .isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM audit_log WHERE request_id = ?"
                + " AND action IN ('REQUEST_CREATED','DOCUMENT_GENERATED')", requestId))
                .isEqualTo(2);
        assertThat(queryLong("SELECT count(*) FROM audit_log WHERE request_id = ?"
                + " AND details::text LIKE '%Maria%'", requestId)).isZero();
    }

    // ------------------------------------------------------------------
    // 2 — Flux conversationnel : 422 puis PATCH avec normalisation de date
    // ------------------------------------------------------------------

    @Test
    @Order(20)
    void missing_information_then_patch_normalizes_date_and_generates() throws Exception {
        HttpResponse<byte[]> missing = post(API, """
                {"documentType":"ATTESTATION_CONCORDANCE",
                 "data":{"prenom":"Maria","nom":"Gomes",
                         "nomIncorrect":"Maria Gomez","nomCorrect":"Maria Gomes"}}
                """);
        assertThat(missing.statusCode()).isEqualTo(422);
        JsonNode missingJson = body(missing);
        assertThat(missingJson.path("code").asText()).isEqualTo("MISSING_INFORMATION");
        List<String> missingFields = new ArrayList<>();
        missingJson.path("missingFields").forEach(node -> missingFields.add(node.asText()));
        assertThat(missingFields).containsExactly("dateNaissance", "lieuNaissance");
        UUID requestId = UUID.fromString(missingJson.path("requestId").asText());

        HttpResponse<byte[]> state = get(API + "/" + requestId);
        assertThat(state.statusCode()).isEqualTo(200);
        assertThat(body(state).path("status").asText()).isEqualTo("MISSING_INFORMATION");

        HttpResponse<byte[]> patched = patch(API + "/" + requestId, """
                {"data":{"dateNaissance":"12/05/1985","lieuNaissance":"Bissau"}}
                """);
        assertThat(patched.statusCode()).isEqualTo(200);
        JsonNode patchedJson = body(patched);
        assertThat(patchedJson.path("status").asText()).isEqualTo("VALIDATED");
        assertThat(patchedJson.path("data").path("dateNaissance").asText())
                .isEqualTo("1985-05-12");
        assertThat(patchedJson.has("missingFields"))
                .as("missingFields doit être présent dans le contrat E3").isTrue();
        assertThat(patchedJson.path("missingFields").isEmpty()).isTrue();

        // Représentation canonique persistée côté PostgreSQL.
        assertThat(queryString("SELECT payload->'data'->>'dateNaissance'"
                + " FROM document_request WHERE request_id = ?", requestId))
                .isEqualTo("1985-05-12");
        assertThat(queryString("SELECT missing_fields::text"
                + " FROM document_request WHERE request_id = ?", requestId))
                .isEqualTo("{}");

        HttpResponse<byte[]> generated = post(API + "/" + requestId + "/generate", null);
        assertThat(generated.statusCode()).isEqualTo(201);
        UUID documentId = UUID.fromString(body(generated).path("documentId").asText());

        HttpResponse<byte[]> download = get(API + "/" + requestId + "/documents/" + documentId);
        assertThat(download.statusCode()).isEqualTo(200);
        String text = poiText(download.body());
        assertThat(text).contains("1985-05-12").contains("Bissau").contains(MARKER);
        assertThat(text).doesNotContain("{{").doesNotContain("}}");

        String storedSha = queryString(
                "SELECT sha256 FROM generated_document WHERE document_id = ?", documentId);
        assertThat(sha256Hex(download.body())).isEqualTo(storedSha);
        assertThat(sha256Hex(Files.readAllBytes(storageRoot.resolve(requestId.toString())
                .resolve(documentId + ".docx")))).isEqualTo(storedSha);
    }

    // ------------------------------------------------------------------
    // 3 — Négatifs HTTP réels
    // ------------------------------------------------------------------

    @Test
    @Order(30)
    void unknown_envelope_key_is_rejected_without_creating_a_request() throws Exception {
        long before = queryLong("SELECT count(*) FROM document_request");
        HttpResponse<byte[]> response = post(API, """
                {"documentType":"ATTESTATION_CONCORDANCE",
                 "data":{"prenom":"Maria","nom":"Gomes"},
                 "champInconnu":"valeur"}
                """);
        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode json = body(response);
        assertThat(json.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json.path("fieldErrors")).hasSize(1);
        assertThat(json.path("fieldErrors").get(0).path("field").asText())
                .isEqualTo("champInconnu");
        assertThat(json.path("fieldErrors").get(0).path("code").asText())
                .isEqualTo("ERR_CHAMP_INCONNU");
        assertThat(queryLong("SELECT count(*) FROM document_request")).isEqualTo(before);
        assertThat(json.path("requestId").isMissingNode()).isTrue();
    }

    @Test
    @Order(40)
    void unknown_request_returns_404() throws Exception {
        HttpResponse<byte[]> response = get(API + "/" + UUID.randomUUID());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body(response).path("code").asText()).isEqualTo("REQUEST_NOT_FOUND");
    }

    @Test
    @Order(50)
    void oversized_payload_returns_413() throws Exception {
        long before = queryLong("SELECT count(*) FROM document_request");
        StringBuilder filler = new StringBuilder();
        filler.append("{\"documentType\":\"ATTESTATION_CONCORDANCE\",\"data\":{\"motif\":\"");
        while (filler.length() < MAX_PAYLOAD_BYTES * 2) {
            filler.append("x");
        }
        filler.append("\"}}");
        HttpResponse<byte[]> response = post(API, filler.toString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(body(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .doesNotContain("Exception").doesNotContain(".java");
        assertThat(queryLong("SELECT count(*) FROM document_request")).isEqualTo(before);
    }

    @Test
    @Order(60)
    void unsupported_content_type_returns_documented_400() throws Exception {
        long before = queryLong("SELECT count(*) FROM document_request");
        HttpResponse<byte[]> response = send("POST", API, "text/plain",
                completeBody());
        // Contrat : Content-Type non supporté ⇒ 400 VALIDATION_ERROR / payload.
        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode json = body(response);
        assertThat(json.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json.path("fieldErrors")).hasSize(1);
        assertThat(json.path("fieldErrors").get(0).path("field").asText())
                .isEqualTo("payload");
        assertThat(queryLong("SELECT count(*) FROM document_request")).isEqualTo(before);
    }

    @Test
    @Order(70)
    void generation_with_missing_information_is_refused() throws Exception {
        HttpResponse<byte[]> missing = post(API, """
                {"documentType":"ATTESTATION_CONCORDANCE",
                 "data":{"prenom":"Maria","nom":"Gomes",
                         "nomIncorrect":"Maria Gomez","nomCorrect":"Maria Gomes"}}
                """);
        assertThat(missing.statusCode()).isEqualTo(422);
        UUID requestId = UUID.fromString(body(missing).path("requestId").asText());

        HttpResponse<byte[]> generated = post(API + "/" + requestId + "/generate", null);
        assertThat(generated.statusCode()).isEqualTo(409);
        JsonNode json = body(generated);
        assertThat(json.path("code").asText()).isEqualTo("INVALID_STATUS");
        assertThat(json.path("status").asText()).isEqualTo("MISSING_INFORMATION");

        assertThat(queryLong("SELECT count(*) FROM generated_document"
                + " WHERE request_id = ?", requestId)).isZero();
        assertThat(queryString("SELECT status FROM document_request WHERE request_id = ?",
                requestId)).isEqualTo("MISSING_INFORMATION");
        assertThat(Files.exists(storageRoot.resolve(requestId.toString()))).isFalse();
    }

    @Test
    @Order(80)
    void unknown_document_returns_404() throws Exception {
        HttpResponse<byte[]> created = post(API, completeBody());
        assertThat(created.statusCode()).isEqualTo(201);
        UUID requestId = UUID.fromString(body(created).path("requestId").asText());
        HttpResponse<byte[]> response = get(API + "/" + requestId + "/documents/"
                + UUID.randomUUID());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body(response).path("code").asText()).isEqualTo("DOCUMENT_NOT_FOUND");
    }

    @Test
    @Order(90)
    void error_bodies_never_leak_stacktrace_path_secret_or_unnecessary_pii() {
        assertThat(NEGATIVE_BODIES)
                .as("les scénarios négatifs 30/40/50/60/70/80 ont tous alimenté la collecte")
                .hasSizeGreaterThanOrEqualTo(6);
        int index = 0;
        for (String errorBody : NEGATIVE_BODIES) {
            assertNoTechnicalLeak("corps négatif #" + (++index), errorBody);
        }
    }

    /**
     * Aucun corps d'erreur ne doit exposer de trace technique, de chemin de
     * fichier, de secret ou de donnée nominative du demandeur (AGENTS.md §13/§14).
     */
    private static void assertNoTechnicalLeak(String label, String errorBody) {
        assertThat(errorBody).as("%s : corps d'erreur vide", label).isNotBlank();
        assertThat(errorBody)
                .as("%s : fuite technique, secrét ou donnée nominative", label)
                .doesNotContain("Exception")
                .doesNotContain("at com.adgendoc")
                .doesNotContain("at org.springframework")
                .doesNotContain(".java:")
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("PhaseH_LocalOnly")
                .doesNotContain("change_me_local_only")
                .doesNotContain("C:\\")
                .doesNotContain("storage")
                .doesNotContain("Maria")
                .doesNotContain("Gomes");
    }

    // ------------------------------------------------------------------
    // 4 — Checksum de template invalide (dernier : altère puis restaure)
    // ------------------------------------------------------------------

    @Test
    @Order(100)
    void invalid_template_checksum_refuses_generation() throws Exception {
        HttpResponse<byte[]> created = post(API, completeBody());
        assertThat(created.statusCode()).isEqualTo(201);
        UUID requestId = UUID.fromString(body(created).path("requestId").asText());

        byte[] original = Files.readAllBytes(templateCopy);
        try {
            byte[] tampered = new byte[original.length + 1];
            System.arraycopy(original, 0, tampered, 0, original.length);
            tampered[original.length] = 0x00;
            Files.write(templateCopy, tampered);

            HttpResponse<byte[]> generated = post(API + "/" + requestId + "/generate", null);
            assertThat(generated.statusCode()).isEqualTo(500);
            String rawBody = new String(generated.body(), StandardCharsets.UTF_8);
            assertThat(JSON.readTree(rawBody).path("code").asText())
                    .isEqualTo("TEMPLATE_NOT_FOUND");
            // Corps le plus risqué (500) : même interdiction de fuite que les autres.
            assertNoTechnicalLeak("500 TEMPLATE_NOT_FOUND", rawBody);

            assertThat(queryLong("SELECT count(*) FROM generated_document"
                    + " WHERE request_id = ?", requestId)).isZero();
            assertThat(queryString("SELECT status FROM document_request"
                    + " WHERE request_id = ?", requestId)).isEqualTo("FAILED");
            assertThat(Files.exists(storageRoot.resolve(requestId.toString()))).isFalse();
        } finally {
            Files.write(templateCopy, original);
        }

        // Restauration vérifiée : la copie redevient strictement le template versionné.
        assertThat(sha256Hex(Files.readAllBytes(templateCopy)))
                .isEqualTo(sha256Hex(Files.readAllBytes(referenceTemplate)));
    }

    // ------------------------------------------------------------------
    // Client HTTP réel (TCP)
    // ------------------------------------------------------------------

    private static HttpResponse<byte[]> post(String path, String body) {
        return send("POST", path, "application/json", body);
    }

    private static HttpResponse<byte[]> patch(String path, String body) {
        return send("PATCH", path, "application/json", body);
    }

    private static HttpResponse<byte[]> get(String path) {
        return send("GET", path, null, null);
    }

    private static HttpResponse<byte[]> send(String method, String path, String contentType,
                                             String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + path))
                    .timeout(Duration.ofSeconds(60));
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                if (contentType != null) {
                    builder.header("Content-Type", contentType);
                }
                builder.method(method, HttpRequest.BodyPublishers.ofString(
                        body == null ? "" : body, StandardCharsets.UTF_8));
            }
            HttpResponse<byte[]> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                synchronized (NEGATIVE_BODIES) {
                    NEGATIVE_BODIES.add(new String(response.body(), StandardCharsets.UTF_8));
                }
            }
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        } catch (Exception exception) {
            throw new AssertionError("Appel HTTP " + method + " " + path + " impossible : "
                    + exception.getClass().getSimpleName(), exception);
        }
    }

    private static JsonNode body(HttpResponse<byte[]> response) {
        try {
            return JSON.readTree(response.body());
        } catch (Exception exception) {
            throw new AssertionError("Corps JSON illisible : "
                    + new String(response.body(), StandardCharsets.UTF_8), exception);
        }
    }

    // ------------------------------------------------------------------
    // PostgreSQL réel (JDBC)
    // ------------------------------------------------------------------

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(JDBC_URL, JDBC_USER, jdbcPassword);
    }

    private static long queryLong(String sql, Object... parameters) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    private static String queryString(String sql, Object... parameters) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters)
            throws Exception {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    // ------------------------------------------------------------------
    // DOCX / SHA-256
    // ------------------------------------------------------------------

    private static String poiText(byte[] docx) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            StringBuilder text = new StringBuilder();
            document.getParagraphs().forEach(paragraph -> text.append(paragraph.getText())
                    .append('\n'));
            document.getTables().forEach(table -> table.getRows().forEach(row ->
                    row.getTableCells().forEach(cell -> cell.getParagraphs()
                            .forEach(paragraph -> text.append(paragraph.getText())
                                    .append('\n')))));
            return text.toString();
        } catch (Exception exception) {
            throw new AssertionError("DOCX illisible par Apache POI", exception);
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static String completeBody() {
        return """
                {"documentType":"ATTESTATION_CONCORDANCE",
                 "data":{"prenom":"Maria","nom":"Gomes","dateNaissance":"1985-05-12",
                         "lieuNaissance":"Bissau","nomIncorrect":"Maria Gomez",
                         "nomCorrect":"Maria Gomes","motif":"Dossier bancaire"},
                 "extraction":{"confidence":0.97,"modelId":"ollama/llama3.1",
                               "promptVersion":"v1"}}
                """;
    }
}
