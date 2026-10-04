package com.adgendoc.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase H-HTTP — contrat de sécurité de la <b>configuration runtime telle
 * qu'emballée</b> (classpath, donc ce qui part réellement en production) :
 *
 * <ul>
 *   <li>aucun secret ni défaut faible committé (AGENTS.md §13) ;</li>
 *   <li>échec <b>explicite</b> au démarrage quand un secret obligatoire est
 *       absent, plutôt qu'un démarrage silencieux avec un mot de passe
 *       devinable (fail-fast) ;</li>
 *   <li>périmètre restreint contrôlé : bind loopback par défaut (contrat API
 *       §1.3, architecture §12) ;</li>
 *   <li>aucune fuite technique vers le client (§12 « Pas de stack trace
 *       client »).</li>
 * </ul>
 *
 * <p>Aucune connexion réseau, aucun Docker : test purement contractuel.</p>
 */
class RuntimeSecurityContractTest {

    private static final String WEAK_MARKER = "change_me_local_only";

    // ------------------------------------------------------------------
    // 1 — Secret obligatoire : aucun défaut committé, échec explicite
    // ------------------------------------------------------------------

    @Test
    void datasource_password_placeholder_has_no_committed_default() {
        String yaml = applicationYaml();
        assertThat(yaml)
                .as("spring.datasource.password doit être sans défaut committé")
                .contains("password: ${SPRING_DATASOURCE_PASSWORD}");
        assertThat(yaml)
                .as("aucun mot de passe par défaut ne doit subsister dans la config emballée")
                .doesNotContain("password: ${SPRING_DATASOURCE_PASSWORD:")
                .doesNotContain(WEAK_MARKER);
    }

    /**
     * Le placeholder seul ne suffit pas : Spring résout les valeurs de
     * configuration <b>lenientement</b> (aucune exception, la property reste
     * littérale ou videe) — vérifié et documenté en Phase H.1. Le fail-fast
     * est donc assuré par {@link DatasourceSecretGuard}, enregistré comme
     * {@code ApplicationContextInitializer} : sans cet enregistrement, le
     * service démarrerait avec un secret absent.
     */
    @Test
    void datasource_fail_fast_guard_is_registered_as_context_initializer() {
        String factories = classpathResource("/META-INF/spring.factories");
        assertThat(factories)
                .as("le garde-fou fail-fast doit être déclaré dans spring.factories")
                .contains("org.springframework.context.ApplicationContextInitializer")
                .contains(DatasourceSecretGuard.class.getName());
        assertThat(DatasourceSecretGuard.class)
                .as("le garde-fou doit être un ApplicationContextInitializer")
                .isAssignableTo(org.springframework.context.ApplicationContextInitializer.class);
    }

    // ------------------------------------------------------------------
    // 2 — Périmètre restreint contrôlé : loopback par défaut
    // ------------------------------------------------------------------

    @Test
    void server_binds_loopback_by_default() {
        assertThat(applicationYaml())
                .as("le serveur doit écouter en loopback par défaut "
                        + "(contrat API §1.3 « périmètre restreint »)")
                .contains("address: ${SERVER_BIND_ADDRESS:127.0.0.1}");
    }

    @Test
    void docker_publishes_the_api_on_loopback_only() {
        String compose = repositoryFile("docker", "docker-compose.yml");
        assertThat(compose)
                .as("la publication du port backend doit être épinglée au loopback")
                .contains("127.0.0.1:8080:8080");
        assertThat(compose)
                .as("publication non épinglée (exposition sur toutes les interfaces)")
                .doesNotContain("\"8080:8080\"");
        assertThat(compose)
                .as("le bind du conteneur doit être ouvrable explicitement pour n8n "
                        + "sur le réseau interne")
                .contains("SERVER_BIND_ADDRESS");
        assertThat(compose)
                .as("le bind du conteneur ne doit PAS hériter de la variable hôte "
                        + "(sinon conteneur loopback-only : n8n cassé, healthcheck vert)")
                .doesNotContain("${SERVER_BIND_ADDRESS:-0.0.0.0}")
                .contains("SERVER_BIND_ADDRESS: \"0.0.0.0\"");
        assertThat(compose)
                .as("le mot de passe PostgreSQL doit rester exigé par compose")
                .contains("SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?");
    }

    // ------------------------------------------------------------------
    // 2bis — Phase H.2 : S-2 (loopback n8n/ollama) + S-3 (clé n8n sans défaut)
    // ------------------------------------------------------------------

    @Test
    void docker_publishes_n8n_and_ollama_on_loopback_only() {
        String compose = repositoryFile("docker", "docker-compose.yml");
        assertThat(compose)
                .as("n8n doit être publié sur 127.0.0.1 uniquement (S-2)")
                .contains("127.0.0.1:5678:5678");
        assertThat(compose)
                .as("ollama doit être publié sur 127.0.0.1 uniquement (S-2)")
                .contains("127.0.0.1:11434:11434");

        // Vérification ligne à ligne des PUBLICATIONS ACTIVES (commentaires
        // exclus) : détecte toute forme courte « host:container », y compris
        // « 0.0.0.0:5678:5678 » ou « 5678:5678 » qu'une assertion de sous-chaîne
        // simple ne détecterait pas.
        assertThat(activePortPublications(compose, 8080))
                .as("le backend doit rester publié en boucle locale")
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("127.0.0.1:"));
        assertThat(activePortPublications(compose, 5678))
                .as("n8n doit rester publié en boucle locale (aucune autre forme)")
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("127.0.0.1:"));
        assertThat(activePortPublications(compose, 11434))
                .as("ollama doit rester publié en boucle locale (aucune autre forme)")
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("127.0.0.1:"));
        assertThat(activePortPublications(compose, 5432))
                .as("PostgreSQL reste NON publié : aucune ligne de publication "
                        + "active pour 5432 (la ligne de debug est commentée)")
                .isEmpty();

        assertThat(compose)
                .as("la communication conteneur→conteneur reste assurée par le "
                        + "réseau interne (aucune coupure de service)")
                .contains("BACKEND_BASE_URL: ${BACKEND_BASE_URL:-http://backend:8080}")
                .contains("OLLAMA_BASE_URL: ${OLLAMA_BASE_URL:-http://ollama:11434}")
                .contains("adgendoc-internal");
    }

    /**
     * Lignes de publication de port <b>actives</b> pour un port donné
     * (commentaires YAML exclus). Détecte la forme courte
     * {@code - "hôte:conteneur[:proto]"} <b>et</b> les formes d'évitement :
     * {@code - "conteneur"} (publication 0.0.0.0 / port éphémère) et
     * {@code - "conteneur:conteneur/proto}". Les lignes de volumes
     * (contenant {@code /} dans le chemin) sont écartées.
     */
    private static List<String> activePortPublications(String compose, int port) {
        String barePort = String.valueOf(port);
        List<String> found = new ArrayList<>();
        for (String raw : compose.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("#") || !line.startsWith("-")) {
                continue;
            }
            String value = line.replaceFirst("^-\\s*", "").replace("\"", "").trim();
            // suffixe de protocole éventuel (/tcp, /udp) : ce n'est pas un chemin
            String candidate = value.replaceFirst("/\\w+$", "");
            if (candidate.contains("/")) {
                continue; // volume ou bind-mount, pas une publication de port
            }
            if (candidate.equals(barePort) || candidate.endsWith(":" + port)) {
                found.add(line);
            }
        }
        return found;
    }

    /** Le détecteur de publication doit lui-même être discriminant. */
    @Test
    void active_port_publication_parser_detects_hostile_forms() {
        String synthetic = """
                services:
                  exposed:
                    ports:
                      - "0.0.0.0:5678:5678"
                      - "5678:5678"
                      - "5678"
                      - "5678:5678/udp"
                      - "127.0.0.1:5678:5678"
                    volumes:
                      - n8n-data:/home/node/.n8n
                  debug:
                    #   - "127.0.0.1:5432:5432"
                """;

        List<String> found = activePortPublications(synthetic, 5678);
        assertThat(found)
                .as("4 formes hostiles + forme loopback détectées, volume ignoré")
                .hasSize(5);
        assertThat(found)
                .anySatisfy(line -> assertThat(line).contains("127.0.0.1:"));
        assertThat(activePortPublications(synthetic, 5432))
                .as("la ligne commentée ne compte pas comme publication")
                .isEmpty();
    }

    @Test
    void n8n_encryption_key_has_no_committed_fallback() {
        String compose = repositoryFile("docker", "docker-compose.yml");
        assertThat(compose)
                .as("N8N_ENCRYPTION_KEY doit être exigée sans aucun défaut (S-3)")
                .contains("N8N_ENCRYPTION_KEY: ${N8N_ENCRYPTION_KEY:?");
        assertThat(compose)
                .as("aucun fallback faible ne doit subsister dans compose")
                .doesNotContain("N8N_ENCRYPTION_KEY:-")
                .doesNotContain("CHANGE_ME_openssl_rand_hex_32");
    }

    @Test
    void env_example_carries_no_real_n8n_key_and_no_hex_secret() throws IOException {
        String envExample = repositoryFile("docker", ".env.example");
        assertThat(envExample)
                .as("placeholder vide : copier le fichier sans le renseigner "
                        + "doit faire échouer compose (fail-fast), jamais hériter "
                        + "d'une clé faible")
                .contains("N8N_ENCRYPTION_KEY=")
                .doesNotContain("N8N_ENCRYPTION_KEY=CHANGE_ME")
                .doesNotContainPattern("\\b[0-9a-fA-F]{64}\\b");
        assertThat(repositoryFile("docker", "docker-compose.yml"))
                .as("aucune clé hexadécimale 256-bit ne doit être committée")
                .doesNotContainPattern("\\b[0-9a-fA-F]{64}\\b");
    }

    @Test
    void docker_env_file_is_gitignored() {
        assertThat(repositoryFile(".gitignore"))
                .as("docker/.env (secrets locaux) doit rester ignoré par Git")
                .containsPattern("(?m)^docker/\\.env$");
    }

    // ------------------------------------------------------------------
    // 3 — Aucune donnée technique sensible dans la configuration emballée
    // ------------------------------------------------------------------

    @Test
    void packaged_main_resources_contain_no_weak_credential() throws IOException {
        Path resources = resolvePath("src/main/resources", "backend/src/main/resources");
        assertThat(resources)
                .as("répertoire des ressources principales introuvable").isNotNull();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(resources)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.contains(WEAK_MARKER)) {
                    offenders.add(resources.relativize(file).toString());
                }
            }
        }
        assertThat(offenders)
                .as("marqueur de mot de passe faible présent dans %s", resources)
                .isEmpty();
    }

    @Test
    void error_responses_never_expose_internals() {
        String yaml = applicationYaml();
        assertThat(yaml)
                .contains("include-stacktrace: never")
                .contains("include-message: never")
                .contains("include-exception: false")
                .contains("show-details: never")
                .contains("show-sql: false");
    }

    /**
     * Phase H.2 (S-1) : Hibernate recopie le message SQL brut — énoncé et,
     * pour les contraintes PostgreSQL, les valeurs concernées (donc PII) — en
     * ERROR via {@code SqlExceptionHelper}. Ce canal doit être coupé dans la
     * configuration emballée : seuls les métadonnées sûres de
     * {@code GlobalExceptionHandler} décrivent une erreur de base.
     */
    @Test
    void hibernate_raw_sql_error_echo_is_silenced() {
        assertThat(applicationYaml())
                .as("l'écho SQL brut d'Hibernate doit être désactivé (S-1)")
                .contains("org.hibernate.engine.jdbc.spi.SqlExceptionHelper: OFF");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String applicationYaml() {
        return classpathResource("/application.yml");
    }

    /** Lecture d'une ressource telle qu'emballée (classpath du module principal). */
    private String classpathResource(String name) {
        try (InputStream stream = RuntimeSecurityContractTest.class
                .getResourceAsStream(name)) {
            assertThat(stream).as("ressource absente du classpath : %s", name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("lecture impossible : " + name, exception);
        }
    }

    private String repositoryFile(String... segments) {
        String relative = String.join("/", segments);
        Path moduleSide = Path.of("..").resolve(relative).normalize();
        Path rootSide = Path.of(relative).normalize();
        Path resolved = Files.isRegularFile(moduleSide) ? moduleSide
                : (Files.isRegularFile(rootSide) ? rootSide : null);
        assertThat(resolved)
                .as("fichier du dépôt introuvable : %s (essayé %s et %s)",
                        relative, moduleSide, rootSide)
                .isNotNull();
        try {
            return Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("lecture impossible : " + resolved, exception);
        }
    }

    /** Résout depuis {@code backend/} (Maven) ou depuis la racine du dépôt. */
    private Path resolvePath(String fromModule, String fromRoot) {
        Path moduleSide = Path.of(fromModule.replace('/', java.io.File.separatorChar));
        if (Files.exists(moduleSide)) {
            return moduleSide;
        }
        Path rootSide = Path.of(fromRoot.replace('/', java.io.File.separatorChar));
        return Files.exists(rootSide) ? rootSide : null;
    }
}
