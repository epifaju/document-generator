package com.adgendoc.api;

import com.adgendoc.application.DocumentGenerationService;
import com.adgendoc.application.NormalizationService;
import com.adgendoc.application.RequestService;
import com.adgendoc.application.ValidationService;
import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.RequestStatus;
import com.adgendoc.domain.Template;
import com.adgendoc.domain.ports.AuditPort;
import com.adgendoc.domain.ports.DocumentStorage;
import com.adgendoc.domain.ports.GeneratedDocumentRepository;
import com.adgendoc.domain.ports.RequestRepository;
import com.adgendoc.domain.ports.TemplateEngine;
import com.adgendoc.domain.ports.TemplateRepository;
import com.adgendoc.infrastructure.config.CorrelationIdFilter;
import com.adgendoc.infrastructure.config.PayloadSizeLimitFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vérification INDEPENDANTE de la Phase E (couche API REST) — agent tester.
 *
 * <p>Couvre spécifiquement : arbitrage F-03 (clé inconnue/réservée en PATCH =
 * erreur structurelle 400, zéro mutation, jamais REJECTED, variantes vide /
 * espaces / null), sécurité de surface de TOUS les corps d'erreur, mapping
 * 400/404/409/422/500, shapes de réponse, X-Correlation-Id, E8 limité à
 * {@code valid/errors} (l'IA ne décide jamais de statut).</p>
 *
 * <p>Services réels + ports en mémoire, MockMvc standalone
 * (architecture §11.1) : aucun Spring context, aucun Docker.</p>
 */
class PhaseEContractVerificationTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";
    private static final String DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final long MAX_PAYLOAD_BYTES = 65536;

    /** Marqueurs de message FR (contrat API §1.2 : message en français). */
    private static final String FRENCH_MARKER =
            "(?i).*(demande|document|erreur|données|donn|manquant|requête|requêt|invalide"
                    + "|impossible|close|introuvable|génér|gener|template|correction|corps).*";

    private final InMemoryRequestRepository requestRepository = new InMemoryRequestRepository();
    private final RecordingAuditPort auditPort = new RecordingAuditPort();
    private final InMemoryGeneratedDocumentRepository documentRepository =
            new InMemoryGeneratedDocumentRepository();
    private final RecordingTemplateRepository templateRepository = new RecordingTemplateRepository();
    private final InMemoryDocumentStorage documentStorage = new InMemoryDocumentStorage();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RequestService requestService;
    private RequestResponseMapper mapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        templateRepository.template = Optional.of(
                new Template(DOCUMENT_TYPE, "1.0", "attestation_concordance_v1.docx",
                        "checksum", true));
        ValidationService validationService = new ValidationService(CLOCK);
        requestService = new RequestService(new NormalizationService(), validationService,
                requestRepository, auditPort, CLOCK);
        DocumentGenerationService generationService = new DocumentGenerationService(
                requestRepository, documentRepository, templateRepository,
                new FixedTemplateEngine(), documentStorage, auditPort, CLOCK);
        mapper = new RequestResponseMapper(validationService, generationService);
        DocumentRequestController controller = new DocumentRequestController(requestService,
                generationService, mapper, objectMapper);
        mockMvc = buildMockMvc(controller);
    }

    // ------------------------------------------------------------------
    // ARBITRAGE F-03 — PATCH : erreur STRUCTURELLE, zéro mutation
    // ------------------------------------------------------------------

    private static Stream<Arguments> f03Variants() {
        return Stream.of(
                Arguments.of("passeport", "\"\""),
                Arguments.of("passeport", "\"   \""),
                Arguments.of("passeport", "null"),
                Arguments.of("referenceDemande", "\"\""),
                Arguments.of("referenceDemande", "\"   \""),
                Arguments.of("referenceDemande", "null"));
    }

    @ParameterizedTest(name = "F-03 PATCH [{0}] = {1} -> 400 structurel, aucune mutation")
    @MethodSource("f03Variants")
    void f03_patch_unknown_or_reserved_key_is_structural_400_without_any_mutation(
            String key, String jsonValue) throws Exception {

        UUID requestId = createMissingRequest();
        DocumentRequest before = requestRepository.store.get(requestId);
        int savesBefore = requestRepository.saveCount;
        List<String> auditBefore = List.copyOf(auditPort.actions);
        Map<String, Object> dataBefore = new LinkedHashMap<>(before.getData());
        List<String> missingBefore = List.copyOf(before.getMissingFields());
        Instant updatedAtBefore = before.getUpdatedAt();
        int versionBefore = before.getVersion();
        RequestStatus statusBefore = before.getStatus();

        MockHttpServletResponse response = performExpecting(
                patch("/api/v1/requests/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + jsonText(key, jsonValue) + "}"),
                400);

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        // Code racine = enum §2.3 ; ERR_* uniquement dans fieldErrors[].code
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).matches(FRENCH_MARKER);
        assertThat(body.get("correlationId").asText())
                .isEqualTo(response.getHeader(CorrelationIdFilter.HEADER));
        assertThat(body.get("missingFields")).isNotNull();
        assertThat(body.get("fieldErrors").size()).isEqualTo(1);
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo(key);
        assertThat(body.get("fieldErrors").get(0).get("code").asText())
                .isEqualTo("referenceDemande".equals(key)
                        ? "ERR_CHAMP_RESERVE" : "ERR_CHAMP_INCONNU");
        assertThat(body.get("requestId").asText()).isEqualTo(requestId.toString());
        // JAMAIS REJECTED : statut courant retourné, inchangé
        assertThat(body.get("status").asText()).isEqualTo("MISSING_INFORMATION");

        // ---- ZÉRO MUTATION ----
        assertThat(requestRepository.saveCount).isEqualTo(savesBefore);
        assertThat(auditPort.actions).isEqualTo(auditBefore);
        DocumentRequest after = requestRepository.store.get(requestId);
        assertThat(after).isSameAs(before);
        assertThat(after.getStatus()).isEqualTo(statusBefore);
        assertThat(after.getData()).isEqualTo(dataBefore);
        assertThat(after.getMissingFields()).isEqualTo(missingBefore);
        assertThat(after.getUpdatedAt()).isEqualTo(updatedAtBefore);
        assertThat(after.getVersion()).isEqualTo(versionBefore);

        // Relecture : agrégat strictement identique côté E2
        MockHttpServletResponse reread = performExpecting(
                get("/api/v1/requests/" + requestId), 200);
        JsonNode rereadBody =
                objectMapper.readTree(reread.getContentAsString(StandardCharsets.UTF_8));
        assertThat(rereadBody.get("status").asText()).isEqualTo("MISSING_INFORMATION");
        assertThat(rereadBody.get("missingFields").get(0).asText()).isEqualTo("dateNaissance");
        assertThat(rereadBody.get("data").has(key)).isFalse();
    }

    @Test
    void f03_nested_data_block_unknown_key_is_structural_400_without_any_mutation()
            throws Exception {
        UUID requestId = createMissingRequest();
        int savesBefore = requestRepository.saveCount;
        List<String> auditBefore = List.copyOf(auditPort.actions);
        Map<String, Object> dataBefore =
                new LinkedHashMap<>(requestRepository.store.get(requestId).getData());

        MockHttpServletResponse response = performExpecting(
                patch("/api/v1/requests/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"passeport\":null}}"),
                400);

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("passeport");
        assertThat(body.get("fieldErrors").get(0).get("code").asText())
                .isEqualTo("ERR_CHAMP_INCONNU");

        assertThat(requestRepository.saveCount).isEqualTo(savesBefore);
        assertThat(auditPort.actions).isEqualTo(auditBefore);
        DocumentRequest after = requestRepository.store.get(requestId);
        assertThat(after.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(after.getData()).isEqualTo(dataBefore);
    }

    private static Stream<Arguments> envelopeVariants() {
        return Stream.of(
                Arguments.of("passeport", "\"\""),
                Arguments.of("passeport", "null"),
                Arguments.of("referenceDemande", "\"   \""),
                Arguments.of("referenceDemande", "null"));
    }

    @ParameterizedTest(name = "F-03 E1 enveloppe [{0}] = {1} -> 400 non persistée")
    @MethodSource("envelopeVariants")
    void f03_create_envelope_unknown_key_is_never_persisted(String key, String jsonValue)
            throws Exception {
        int savesBefore = requestRepository.saveCount;

        MockHttpServletResponse response = performExpecting(
                post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"" + DOCUMENT_TYPE + "\","
                                + jsonText(key, jsonValue) + ","
                                + "\"data\":{\"prenom\":\"Maria\"}}"),
                400);

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo(key);
        assertThat(body.get("fieldErrors").get(0).get("code").asText())
                .isEqualTo("referenceDemande".equals(key)
                        ? "ERR_CHAMP_RESERVE" : "ERR_CHAMP_INCONNU");
        assertThat(body.has("requestId")).isFalse();
        assertThat(body.has("status")).isFalse();
        assertThat(requestRepository.saveCount).isEqualTo(savesBefore);
        assertThat(auditPort.actions).isEmpty();
    }

    // ------------------------------------------------------------------
    // Sécurité de surface — TOUS les corps d'erreur testés
    // ------------------------------------------------------------------

    @Test
    void every_error_body_is_free_of_technical_detail_credentials_and_pii() throws Exception {
        List<MockHttpServletResponse> errors = new ArrayList<>();

        // 400 — JSON malformé
        errors.add(performExpecting(post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentType\":\"ATTESTATION_CONCORDANCE\",\"data\":{"), 400));
        // 400 — type de document non supporté (non persistée)
        errors.add(performExpecting(post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentType\":\"ACTE_NAISSANCE\",\"data\":{}}"), 400));
        // 400 — clé inconnue dans data (persistée REJECTED)
        errors.add(performExpecting(post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody().replace("\"motif\"", "\"passeport\"")), 400));
        // 400 — bean validation (bloc data absent)
        errors.add(performExpecting(post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentType\":\"ATTESTATION_CONCORDANCE\"}"), 400));
        // 400 — content-type non JSON
        errors.add(performExpecting(post("/api/v1/requests")
                .contentType(MediaType.TEXT_PLAIN).content(validBody()), 400));
        // 400 — path param non UUID
        errors.add(performExpecting(get("/api/v1/requests/not-a-uuid"), 400));
        // 413 — corps trop volumineux
        errors.add(performExpecting(post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Length", String.valueOf(MAX_PAYLOAD_BYTES + 1))
                .content(validBody()), 413));

        UUID missingId = createMissingRequest();
        UUID ruleId = createMissingRequest();
        UUID rejectedId = createRejectedRequest();
        UUID validatedId = createValidatedRequest();

        // 422 — champs obligatoires manquants
        errors.add(performExpecting(post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithoutDates()), 422));
        // 400 — clé inconnue en PATCH (F-03, zéro mutation)
        errors.add(performExpecting(patch("/api/v1/requests/" + missingId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"passeport\":null}"), 400));
        // 400 — règle métier violée par les données fusionnées
        // (les 6 champs obligatoires deviennent présents -> phase de format atteinte)
        errors.add(performExpecting(patch("/api/v1/requests/" + ruleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dateNaissance\":\"1985-05-12\",\"lieuNaissance\":\"Bissau\","
                        + "\"nom\":\"Gomes@123\"}"), 400));
        // 409 — PATCH sur demande close
        errors.add(performExpecting(patch("/api/v1/requests/" + rejectedId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lieuNaissance\":\"Bissau\"}"), 409));
        // 404 — demande inconnue (E2)
        errors.add(performExpecting(get("/api/v1/requests/" + UUID.randomUUID()), 404));
        // 404 — document inconnu (E6)
        errors.add(performExpecting(get("/api/v1/requests/" + validatedId + "/documents/"
                + UUID.randomUUID()), 404));
        // 409 — validate sur demande REJECTED
        errors.add(performExpecting(
                post("/api/v1/requests/" + rejectedId + "/validate"), 409));
        // 409 — generate sur demande MISSING_INFORMATION
        errors.add(performExpecting(
                post("/api/v1/requests/" + missingId + "/generate"), 409));
        // 500 — template absent
        templateRepository.template = Optional.empty();
        UUID noTemplateId = createValidatedRequest();
        errors.add(performExpecting(
                post("/api/v1/requests/" + noTemplateId + "/generate"), 500));
        // 500 — échec de fusion (DOCUMENT_GENERATION_ERROR)
        templateRepository.template = Optional.of(
                new Template(DOCUMENT_TYPE, "1.0", "attestation_concordance_v1.docx",
                        "checksum", true));
        MockMvc failingMvc = buildMockMvc(new DocumentRequestController(requestService,
                failingGenerationService(), mapper, objectMapper));
        UUID failingId = createValidatedRequest();
        MockHttpServletResponse failing = failingMvc
                .perform(post("/api/v1/requests/" + failingId + "/generate"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse();
        assertThat(failing.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"DOCUMENT_GENERATION_ERROR\"");
        errors.add(failing);

        assertThat(errors).hasSize(17);
        for (MockHttpServletResponse response : errors) {
            String body = response.getContentAsString(StandardCharsets.UTF_8);
            String scenario = response.getStatus() + " : "
                    + body.substring(0, Math.min(90, body.length()));

            // Pas de stack trace / détail technique / nom d'exception / chemin / credential
            assertThat(body).as(scenario)
                    .doesNotContain("java.")
                    .doesNotContain("at com.adgendoc")
                    .doesNotContain("Exception")
                    .doesNotContain("stackTrace")
                    .doesNotContain(".java")
                    .doesNotContain("\\Users\\")
                    .doesNotContain("/backend/src")
                    .doesNotContain("C:\\")
                    .doesNotContain("password")
                    .doesNotContain("secret")
                    .doesNotContain("Bearer")
                    .doesNotContain("Maria")
                    .doesNotContain("Gomes")
                    .doesNotContain("Bissau");

            // Content-Type JSON + X-Correlation-Id + message FR + champs obligatoires
            assertThat(response.getContentType()).as(scenario).contains("application/json");
            assertThat(response.getHeader(CorrelationIdFilter.HEADER)).as(scenario).isNotBlank();

            JsonNode node = objectMapper.readTree(body);
            assertThat(node.has("code")).as(scenario).isTrue();
            assertThat(node.get("code").asText()).as(scenario)
                    .matches("^[A-Z_]+$");
            assertThat(node.get("message").asText()).as(scenario).matches(FRENCH_MARKER);
            assertThat(node.get("correlationId").asText()).as(scenario)
                    .isEqualTo(response.getHeader(CorrelationIdFilter.HEADER));
            assertThat(node.has("missingFields")).as(scenario).isTrue();
            assertThat(node.has("fieldErrors")).as(scenario).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // Mapping des erreurs — codes restants non couverts
    // ------------------------------------------------------------------

    @Test
    void e4_validate_on_generated_request_returns_409_invalid_status() throws Exception {
        UUID requestId = createValidatedRequest();
        performExpecting(post("/api/v1/requests/" + requestId + "/generate"), 201);

        MockHttpServletResponse response = performExpecting(
                post("/api/v1/requests/" + requestId + "/validate"), 409);

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo("INVALID_STATUS");
        assertThat(body.get("message").asText()).contains("GENERATED");
        assertThat(body.get("status").asText()).isEqualTo("GENERATED");
        assertThat(body.get("requestId").asText()).isEqualTo(requestId.toString());
    }

    @Test
    void e5_generation_failure_returns_500_document_generation_error_and_fails_request()
            throws Exception {
        UUID requestId = createValidatedRequest();
        MockMvc failingMvc = buildMockMvc(new DocumentRequestController(requestService,
                failingGenerationService(), mapper, objectMapper));

        MockHttpServletResponse response = failingMvc
                .perform(post("/api/v1/requests/" + requestId + "/generate"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse();

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo("DOCUMENT_GENERATION_ERROR");
        assertThat(body.get("message").asText()).matches(FRENCH_MARKER);
        assertThat(body.get("correlationId").asText())
                .isEqualTo(response.getHeader(CorrelationIdFilter.HEADER));
        assertThat(requestRepository.store.get(requestId).getStatus())
                .isEqualTo(RequestStatus.FAILED);
    }

    @Test
    void unexpected_exception_returns_500_internal_error_without_leaking_details()
            throws Exception {
        requestRepository.poisonId = UUID.randomUUID();

        MockHttpServletResponse response = performExpecting(
                get("/api/v1/requests/" + requestRepository.poisonId), 500);

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(node.get("message").asText()).matches(FRENCH_MARKER);
        assertThat(body)
                .doesNotContain("boom")
                .doesNotContain("Secret")
                .doesNotContain("IllegalState")
                .doesNotContain("java.")
                .doesNotContain("Exception")
                .doesNotContain("at com.adgendoc");
        assertThat(node.get("correlationId").asText())
                .isEqualTo(response.getHeader(CorrelationIdFilter.HEADER));
    }

    @Test
    void e4_validate_on_unknown_request_returns_404_request_not_found() throws Exception {
        MockHttpServletResponse response = performExpecting(
                post("/api/v1/requests/" + UUID.randomUUID() + "/validate"), 404);

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo("REQUEST_NOT_FOUND");
        assertThat(body.has("requestId")).isFalse();
    }

    // ------------------------------------------------------------------
    // X-Correlation-Id sur les réponses de succès (§1.1 : toujours renvoyé)
    // ------------------------------------------------------------------

    @Test
    void correlation_id_header_is_present_on_success_responses() throws Exception {
        MockHttpServletResponse created = performExpecting(
                post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "n8n-trace-1")
                        .content(validBody()),
                201);
        assertThat(created.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("n8n-trace-1");
        UUID requestId = UUID.fromString(
                objectMapper.readTree(created.getContentAsString(StandardCharsets.UTF_8))
                        .get("requestId").asText());

        MockHttpServletResponse fetched = performExpecting(
                get("/api/v1/requests/" + requestId)
                        .header("X-Correlation-Id", "n8n-trace-2"), 200);
        assertThat(fetched.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("n8n-trace-2");
    }

    @Test
    void invalid_correlation_id_is_replaced_by_a_generated_uuid() throws Exception {
        MockHttpServletResponse response = performExpecting(
                get("/api/v1/requests/" + UUID.randomUUID())
                        .header("X-Correlation-Id", "invalid id // with spaces & symbols!"),
                404);
        assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    // ------------------------------------------------------------------
    // Shapes des corps de réponse (§2.2, §2.5, §2.6, §3.7)
    // ------------------------------------------------------------------

    @Test
    void e1_success_body_shape_exactly_matches_contract_2_2() throws Exception {
        MockHttpServletResponse response = performExpecting(
                post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()), 201);
        assertThat(response.getContentType()).contains("application/json");

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "requestId", "referenceDemande", "documentType", "status", "missingFields",
                "fieldErrors", "data", "documents", "createdAt", "updatedAt");
        assertThat(body.get("requestId").asText()).isEqualTo(body.get("referenceDemande").asText());
        assertThat(body.get("createdAt").asText())
                .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");
        assertThat(body.get("updatedAt").asText())
                .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");
    }

    @Test
    void e5_success_body_shape_exactly_matches_contract_2_5() throws Exception {
        UUID requestId = createValidatedRequest();
        MockHttpServletResponse response = performExpecting(
                post("/api/v1/requests/" + requestId + "/generate"), 201);

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "requestId", "documentId", "status", "fileName", "contentType", "downloadPath",
                "generatedAt");
        assertThat(body.get("status").asText()).isEqualTo("GENERATED");
        assertThat(body.get("contentType").asText()).isEqualTo(DOCX_MIME_TYPE);
    }

    @Test
    void e7_health_body_shape_exactly_matches_contract_2_7() throws Exception {
        MockHttpServletResponse response = performExpecting(get("/api/v1/health"), 200);
        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(fieldNames(body)).containsExactly("status");
        assertThat(body.get("status").asText()).isEqualTo("UP");
    }

    @Test
    void e8_response_only_exposes_valid_correlation_and_errors_never_a_status()
            throws Exception {
        MockHttpServletResponse invalid = performExpecting(
                post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"ACTE_NAISSANCE\",\"confidence\":0.9,"
                                + "\"data\":{},\"missingFields\":[]}"),
                400);

        JsonNode body = objectMapper.readTree(invalid.getContentAsString(StandardCharsets.UTF_8));
        // L'IA ne décide jamais de statut : aucune clé de statut, ni requestId
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("valid", "correlationId", "errors");
        assertThat(body.get("valid").asBoolean()).isFalse();
        assertThat(body.get("errors").size()).isGreaterThan(0);
        assertThat(body.get("errors").get(0).has("path")).isTrue();
        assertThat(body.get("errors").get(0).has("code")).isTrue();
        assertThat(body.get("errors").get(0).has("message")).isTrue();

        MockHttpServletResponse valid = performExpecting(
                post("/api/v1/extraction/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"documentType":"ATTESTATION_CONCORDANCE","confidence":0.97,
                                 "data":{"prenom":"Maria","nom":"Gomes",
                                         "dateNaissance":"1985-05-12","lieuNaissance":"Bissau",
                                         "nomIncorrect":"Maria Gomez","nomCorrect":"Maria Gomes"},
                                 "missingFields":[]}
                                """),
                200);
        JsonNode validBody = objectMapper.readTree(
                valid.getContentAsString(StandardCharsets.UTF_8));
        assertThat(fieldNames(validBody)).containsExactlyInAnyOrder(
                "valid", "correlationId", "errors");
        assertThat(validBody.get("valid").asBoolean()).isTrue();
    }

    @Test
    void missing_information_body_lists_canonical_order_and_no_field_errors() throws Exception {
        MockHttpServletResponse response = performExpecting(
                post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutDates()), 422);

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "code", "message", "correlationId", "missingFields", "requestId", "status",
                "fieldErrors");
        assertThat(body.get("code").asText()).isEqualTo("MISSING_INFORMATION");
        List<String> missing = new ArrayList<>();
        body.get("missingFields").forEach(node -> missing.add(node.asText()));
        assertThat(missing).containsExactly("dateNaissance", "lieuNaissance");
        assertThat(body.get("fieldErrors")).isEmpty();
        assertThat(body.get("status").asText()).isEqualTo("MISSING_INFORMATION");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String jsonText(String key, String jsonValue) {
        return "\"" + key + "\":" + jsonValue;
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private MockHttpServletResponse performExpecting(
            org.springframework.test.web.servlet.RequestBuilder request, int expectedStatus)
            throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();
    }

    private MockMvc buildMockMvc(DocumentRequestController controller) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        ExtractionController extractionController = new ExtractionController(
                new com.adgendoc.application.ExtractionValidationService(
                        "classpath:prompts/extraction/attestation_concordance.schema.json"));
        return MockMvcBuilders.standaloneSetup(controller, extractionController,
                        new HealthController(null))
                .setControllerAdvice(new GlobalExceptionHandler(requestService))
                .setValidator(validator)
                .addFilters(new CorrelationIdFilter(),
                        new PayloadSizeLimitFilter(MAX_PAYLOAD_BYTES))
                .build();
    }

    private DocumentGenerationService failingGenerationService() {
        return new DocumentGenerationService(requestRepository, documentRepository,
                templateRepository, new EmptyTemplateEngine(), documentStorage, auditPort, CLOCK);
    }

    private String validBody() {
        return """
                {"documentType":"ATTESTATION_CONCORDANCE",
                 "data":{"prenom":"Maria","nom":"Gomes","dateNaissance":"1985-05-12",
                         "lieuNaissance":"Bissau","nomIncorrect":"Maria Gomez",
                         "nomCorrect":"Maria Gomes","motif":"Dossier bancaire"},
                 "extraction":{"confidence":0.97,"modelId":"ollama/llama3.1",
                               "promptVersion":"v1"}}
                """;
    }

    private String bodyWithoutDates() {
        return """
                {"documentType":"ATTESTATION_CONCORDANCE",
                 "data":{"prenom":"Maria","nom":"Gomes",
                         "nomIncorrect":"Maria Gomez","nomCorrect":"Maria Gomes"}}
                """;
    }

    private UUID createValidatedRequest() throws Exception {
        MockHttpServletResponse response = performExpecting(
                post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()), 201);
        return UUID.fromString(objectMapper
                .readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .get("requestId").asText());
    }

    private UUID createMissingRequest() throws Exception {
        MockHttpServletResponse response = performExpecting(
                post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutDates()), 422);
        return UUID.fromString(objectMapper
                .readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .get("requestId").asText());
    }

    private UUID createRejectedRequest() throws Exception {
        MockHttpServletResponse response = performExpecting(
                post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON)
                        .content(validBody().replace("Gomes", "Gomes@123")), 400);
        return UUID.fromString(objectMapper
                .readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .get("requestId").asText());
    }

    // ------------------------------------------------------------------
    // Ports en mémoire
    // ------------------------------------------------------------------

    private static final class InMemoryRequestRepository implements RequestRepository {

        private final Map<UUID, DocumentRequest> store = new LinkedHashMap<>();
        private int saveCount;
        private UUID poisonId;

        @Override
        public DocumentRequest save(DocumentRequest request) {
            saveCount++;
            store.put(request.getRequestId(), request);
            return request;
        }

        @Override
        public Optional<DocumentRequest> findById(UUID requestId) {
            if (requestId != null && requestId.equals(poisonId)) {
                throw new IllegalStateException(
                        "boom interne Secret at com.adgendoc.internal.Tools");
            }
            return Optional.ofNullable(store.get(requestId));
        }
    }

    private static final class InMemoryGeneratedDocumentRepository
            implements GeneratedDocumentRepository {

        private final Map<UUID, com.adgendoc.domain.GeneratedDocument> store = new LinkedHashMap<>();

        @Override
        public com.adgendoc.domain.GeneratedDocument save(
                com.adgendoc.domain.GeneratedDocument document) {
            store.put(document.getDocumentId(), document);
            return document;
        }

        @Override
        public Optional<com.adgendoc.domain.GeneratedDocument> findByIdAndRequestId(
                UUID documentId, UUID requestId) {
            com.adgendoc.domain.GeneratedDocument document = store.get(documentId);
            return document != null && document.getRequestId().equals(requestId)
                    ? Optional.of(document)
                    : Optional.empty();
        }

        @Override
        public List<com.adgendoc.domain.GeneratedDocument> findByRequestId(UUID requestId) {
            List<com.adgendoc.domain.GeneratedDocument> documents = new ArrayList<>();
            for (com.adgendoc.domain.GeneratedDocument document : store.values()) {
                if (document.getRequestId().equals(requestId)) {
                    documents.add(document);
                }
            }
            return documents;
        }
    }

    private static final class RecordingTemplateRepository implements TemplateRepository {

        private Optional<Template> template = Optional.empty();

        @Override
        public Optional<Template> findActiveByCode(String code) {
            return template;
        }
    }

    private static final class InMemoryDocumentStorage implements DocumentStorage {

        private final Map<String, byte[]> files = new LinkedHashMap<>();

        @Override
        public String store(UUID requestId, UUID documentId, byte[] content) {
            String storagePath = requestId + "/" + documentId + ".docx";
            files.put(storagePath, content);
            return storagePath;
        }

        @Override
        public byte[] read(String storagePath) {
            byte[] content = files.get(storagePath);
            if (content == null) {
                throw new IllegalStateException("Fichier absent : " + storagePath);
            }
            return content;
        }
    }

    private static final class FixedTemplateEngine implements TemplateEngine {

        @Override
        public byte[] merge(Template template, Map<String, String> templateVariables) {
            return "Attestation {{prenom}}".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class EmptyTemplateEngine implements TemplateEngine {

        @Override
        public byte[] merge(Template template, Map<String, String> templateVariables) {
            return new byte[0];
        }
    }

    private static final class RecordingAuditPort implements AuditPort {

        private final List<String> actions = new ArrayList<>();
        private final List<Map<String, Object>> details = new ArrayList<>();

        @Override
        public void record(UUID requestId, String action, String actor, String correlationId,
                           Map<String, Object> record) {
            actions.add(action);
            details.add(new LinkedHashMap<>(record));
        }
    }
}
