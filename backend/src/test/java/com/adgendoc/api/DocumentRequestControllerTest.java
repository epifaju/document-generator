package com.adgendoc.api;

import com.adgendoc.application.DocumentGenerationService;
import com.adgendoc.application.NormalizationService;
import com.adgendoc.application.RequestService;
import com.adgendoc.application.ValidationService;
import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.GeneratedDocument;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

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
 * Tests MockMvc (standalone) des endpoints E1–E7 : services réels + ports
 * en mémoire, aucun Spring context, aucun Docker (architecture §11.1).
 */
class DocumentRequestControllerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";
    private static final String DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final long MAX_PAYLOAD_BYTES = 65536;

    private final InMemoryRequestRepository requestRepository = new InMemoryRequestRepository();
    private final RecordingAuditPort auditPort = new RecordingAuditPort();
    private final InMemoryGeneratedDocumentRepository documentRepository =
            new InMemoryGeneratedDocumentRepository();
    private final RecordingTemplateRepository templateRepository = new RecordingTemplateRepository();
    private final InMemoryDocumentStorage documentStorage = new InMemoryDocumentStorage();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RequestService requestService;
    private DocumentGenerationService documentGenerationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        templateRepository.template = Optional.of(
                new Template(DOCUMENT_TYPE, "1.0", "attestation_concordance_v1.docx",
                        "checksum", true));
        ValidationService validationService = new ValidationService(CLOCK);
        requestService = new RequestService(new NormalizationService(), validationService,
                requestRepository, auditPort, CLOCK);
        documentGenerationService = new DocumentGenerationService(requestRepository,
                documentRepository, templateRepository, new FixedTemplateEngine(),
                documentStorage, auditPort, CLOCK);
        RequestResponseMapper mapper =
                new RequestResponseMapper(validationService, documentGenerationService);
        DocumentRequestController controller = new DocumentRequestController(requestService,
                documentGenerationService, mapper, objectMapper);
        mockMvc = buildMockMvc(controller, requestService, null);
    }

    private MockMvc buildMockMvc(DocumentRequestController controller,
                                 RequestService service, DataSource dataSource) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller, new HealthController(dataSource))
                .setControllerAdvice(new GlobalExceptionHandler(service))
                .setValidator(validator)
                .addFilters(new CorrelationIdFilter(),
                        new PayloadSizeLimitFilter(MAX_PAYLOAD_BYTES))
                .build();
    }

    // ------------------------------------------------------------------
    // E1 — POST /api/v1/requests
    // ------------------------------------------------------------------

    @Test
    void e1_valid_payload_returns_201_validated() throws Exception {
        String response = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.requestId").value(matchesPattern(
                        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
                .andExpect(jsonPath("$.documentType").value(DOCUMENT_TYPE))
                .andExpect(jsonPath("$.missingFields").isEmpty())
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.documents").isEmpty())
                .andExpect(jsonPath("$.data.prenom").value("Maria"))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(
                        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(objectMapper.readTree(response).get("referenceDemande").asText())
                .isEqualTo(objectMapper.readTree(response).get("requestId").asText());
        assertThat(requestRepository.saveCount).isEqualTo(1);
        DocumentRequest stored = requestRepository.store.values().iterator().next();
        assertThat(stored.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(stored.getCorrelationId()).isNotBlank();
        assertThat(auditPort.actions).containsExactly("REQUEST_CREATED");
    }

    @Test
    void e1_missing_fields_returns_422_in_canonical_order() throws Exception {
        String body = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"documentType":"ATTESTATION_CONCORDANCE",
                                 "data":{"prenom":"Maria","nom":"Gomes",
                                         "nomIncorrect":"Maria Gomez",
                                         "nomCorrect":"Maria Gomes"}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MISSING_INFORMATION"))
                .andExpect(jsonPath("$.message").value("Information manquante pour générer le document."))
                .andExpect(jsonPath("$.missingFields[0]").value("dateNaissance"))
                .andExpect(jsonPath("$.missingFields[1]").value("lieuNaissance"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.status").value("MISSING_INFORMATION"))
                .andExpect(jsonPath("$.requestId").value(matchesPattern(
                        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(not(containsString("java."))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("Exception");
        assertThat(requestRepository.saveCount).isEqualTo(1);
    }

    @Test
    void e1_blank_required_field_is_missing_information() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"documentType":"ATTESTATION_CONCORDANCE",
                                 "data":{"prenom":"Maria","nom":"   ",
                                         "dateNaissance":"1985-05-12","lieuNaissance":"Bissau",
                                         "nomIncorrect":"Maria Gomez","nomCorrect":"Maria Gomes"}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingFields[0]").value("nom"))
                .andExpect(jsonPath("$.status").value("MISSING_INFORMATION"));
    }

    @Test
    void e1_french_date_is_normalized_to_iso() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody().replace("1985-05-12", "12/05/1985")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dateNaissance").value("1985-05-12"));
    }

    @Test
    void e1_invalid_calendar_date_returns_400_rejected() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody().replace("1985-05-12", "31/02/1985")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dateNaissance"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("ERR_DATE_CALENDRIER_INVALIDE"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.missingFields").isEmpty());

        assertThat(requestRepository.saveCount).isEqualTo(1);
        DocumentRequest stored = requestRepository.store.values().iterator().next();
        assertThat(stored.getStatus()).isEqualTo(RequestStatus.REJECTED);
    }

    @Test
    void e1_unsupported_document_type_is_not_persisted() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"documentType":"ACTE_NAISSANCE","data":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("documentType"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("ERR_DOCUMENT_TYPE_NON_SUPPORTE"))
                .andExpect(jsonPath("$.requestId").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(content().string(not(containsString("java."))));

        assertThat(requestRepository.saveCount).isZero();
    }

    @Test
    void e1_unknown_data_key_returns_400_rejected_persisted() throws Exception {
        String payload = validBody().replace("\"motif\"", "\"passeport\"");

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("passeport"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ERR_CHAMP_INCONNU"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(requestRepository.saveCount).isEqualTo(1);
        DocumentRequest stored = requestRepository.store.values().iterator().next();
        assertThat(stored.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(stored.getData()).doesNotContainKey("passeport");
    }

    @Test
    void e1_reserved_root_key_is_rejected_not_persisted() throws Exception {
        String payload = validBody().replace("\"data\":", "\"referenceDemande\":\"x\",\"data\":");

        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("referenceDemande"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ERR_CHAMP_RESERVE"))
                .andExpect(jsonPath("$.requestId").doesNotExist());

        assertThat(requestRepository.saveCount).isZero();
    }

    @Test
    void e1_missing_data_block_returns_400_bean_validation() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"ATTESTATION_CONCORDANCE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("data"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ERR_PAYLOAD_INVALIDE"))
                .andExpect(jsonPath("$.requestId").doesNotExist());

        assertThat(requestRepository.saveCount).isZero();
    }

    @Test
    void e1_malformed_json_returns_400_without_stack_trace() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"ATTESTATION_CONCORDANCE\",\"data\":{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("payload"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ERR_PAYLOAD_INVALIDE"))
                .andExpect(jsonPath("$.requestId").doesNotExist())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("at com.adgendoc"))));

        assertThat(requestRepository.saveCount).isZero();
    }

    @Test
    void e1_non_json_content_type_returns_400_payload_invalid() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ERR_PAYLOAD_INVALIDE"));

        assertThat(requestRepository.saveCount).isZero();
    }

    @Test
    void e1_oversized_payload_returns_413() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Length", String.valueOf(MAX_PAYLOAD_BYTES + 1))
                        .content(validBody()))
                .andExpect(status().isRequestEntityTooLarge())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(not(containsString("java."))));

        assertThat(requestRepository.saveCount).isZero();
    }

    // ------------------------------------------------------------------
    // E2 — GET /api/v1/requests/{requestId}
    // ------------------------------------------------------------------

    @Test
    void e2_returns_request_status() throws Exception {
        UUID requestId = createMissingRequest();

        mockMvc.perform(get("/api/v1/requests/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("MISSING_INFORMATION"))
                .andExpect(jsonPath("$.missingFields[0]").value("dateNaissance"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void e2_unknown_request_returns_404_without_technical_detail() throws Exception {
        mockMvc.perform(get("/api/v1/requests/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void e2_after_generation_returns_documents_array() throws Exception {
        UUID requestId = createRequest(validBody());
        mockMvc.perform(post("/api/v1/requests/" + requestId + "/generate"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/requests/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].fileName")
                        .value("attestation-concordance-" + requestId + ".docx"))
                .andExpect(jsonPath("$.documents[0].contentType").value(DOCX_MIME_TYPE))
                .andExpect(jsonPath("$.documents[0].documentId")
                        .value(matchesPattern(
                                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
                .andExpect(jsonPath("$.documents[0].generatedAt").value(matchesPattern(
                        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")));
    }

    // ------------------------------------------------------------------
    // E3 — PATCH /api/v1/requests/{requestId}
    // ------------------------------------------------------------------

    @Test
    void e3_flat_patch_completes_missing_information() throws Exception {
        UUID requestId = createMissingRequest();

        mockMvc.perform(patch("/api/v1/requests/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dateNaissance":"12/05/1985","lieuNaissance":"Bissau"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.missingFields").isEmpty())
                .andExpect(jsonPath("$.data.dateNaissance").value("1985-05-12"))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()));
    }

    @Test
    void e3_nested_patch_form_is_supported() throws Exception {
        UUID requestId = createMissingRequest();

        mockMvc.perform(patch("/api/v1/requests/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"data":{"dateNaissance":"1985-05-12","lieuNaissance":"Bissau"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.missingFields").isEmpty());
    }

    @Test
    void e3_partial_patch_keeps_missing_information() throws Exception {
        UUID requestId = createMissingRequest();

        mockMvc.perform(patch("/api/v1/requests/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateNaissance\":\"1985-05-12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MISSING_INFORMATION"))
                .andExpect(jsonPath("$.missingFields[0]").value("lieuNaissance"));
    }

    @Test
    void e3_unknown_key_is_structural_and_does_not_modify_request() throws Exception {
        UUID requestId = createMissingRequest();
        int savesBefore = requestRepository.saveCount;

        mockMvc.perform(patch("/api/v1/requests/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passeport\":\"X123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("passeport"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ERR_CHAMP_INCONNU"))
                .andExpect(jsonPath("$.status").value("MISSING_INFORMATION"))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()));

        assertThat(requestRepository.saveCount).isEqualTo(savesBefore);
        DocumentRequest stored = requestRepository.store.get(requestId);
        assertThat(stored.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(stored.getData()).doesNotContainKey("passeport");

        mockMvc.perform(get("/api/v1/requests/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MISSING_INFORMATION"))
                .andExpect(jsonPath("$.missingFields[0]").value("dateNaissance"));
    }

    @Test
    void e3_reserved_key_is_refused_without_mutation() throws Exception {
        UUID requestId = createMissingRequest();
        int savesBefore = requestRepository.saveCount;

        mockMvc.perform(patch("/api/v1/requests/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"referenceDemande\":\"autre\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("referenceDemande"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ERR_CHAMP_RESERVE"));

        assertThat(requestRepository.saveCount).isEqualTo(savesBefore);
    }

    @Test
    void e3_business_rule_violation_rejects_request() throws Exception {
        UUID requestId = createMissingRequest();

        mockMvc.perform(patch("/api/v1/requests/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dateNaissance":"1985-05-12","lieuNaissance":"Bissau",
                                 "nom":"Gomes@123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("nom"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("ERR_FORMAT_TEXTE_INVALIDE"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()));

        DocumentRequest stored = requestRepository.store.get(requestId);
        assertThat(stored.getStatus()).isEqualTo(RequestStatus.REJECTED);
    }

    @Test
    void e3_on_terminal_request_returns_409_request_already_closed() throws Exception {
        UUID requestId = createRejectedRequest();

        mockMvc.perform(patch("/api/v1/requests/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lieuNaissance\":\"Bissau\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_CLOSED"))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void e3_unknown_request_returns_404() throws Exception {
        mockMvc.perform(patch("/api/v1/requests/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lieuNaissance\":\"Bissau\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // E4 — POST /api/v1/requests/{requestId}/validate
    // ------------------------------------------------------------------

    @Test
    void e4_validate_on_validated_request_returns_200() throws Exception {
        UUID requestId = createRequest(validBody());

        mockMvc.perform(post("/api/v1/requests/" + requestId + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()));
    }

    @Test
    void e4_validate_with_missing_fields_returns_422() throws Exception {
        UUID requestId = createMissingRequest();

        mockMvc.perform(post("/api/v1/requests/" + requestId + "/validate"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MISSING_INFORMATION"))
                .andExpect(jsonPath("$.missingFields[0]").value("dateNaissance"))
                .andExpect(jsonPath("$.status").value("MISSING_INFORMATION"));
    }

    @Test
    void e4_validate_on_rejected_request_returns_409_invalid_status() throws Exception {
        UUID requestId = createRejectedRequest();

        mockMvc.perform(post("/api/v1/requests/" + requestId + "/validate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"))
                .andExpect(jsonPath("$.message").value(containsString("REJECTED")))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // ------------------------------------------------------------------
    // E5 — POST /api/v1/requests/{requestId}/generate
    // ------------------------------------------------------------------

    @Test
    void e5_generate_returns_201_generated() throws Exception {
        UUID requestId = createRequest(validBody());

        String response = mockMvc.perform(post("/api/v1/requests/" + requestId + "/generate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.fileName")
                        .value("attestation-concordance-" + requestId + ".docx"))
                .andExpect(jsonPath("$.contentType").value(DOCX_MIME_TYPE))
                .andExpect(jsonPath("$.downloadPath").value(matchesPattern(
                        "^/api/v1/requests/" + requestId
                                + "/documents/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                                + "[0-9a-f]{4}-[0-9a-f]{12}$")))
                .andExpect(jsonPath("$.documentId").isNotEmpty())
                .andExpect(jsonPath("$.generatedAt").value(matchesPattern(
                        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String documentId = objectMapper.readTree(response).get("documentId").asText();
        assertThat(documentRepository.store.get(UUID.fromString(documentId)).getRequestId())
                .isEqualTo(requestId);
    }

    @Test
    void e5_generate_on_missing_information_returns_409_citing_statuses() throws Exception {
        UUID requestId = createMissingRequest();

        mockMvc.perform(post("/api/v1/requests/" + requestId + "/generate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"))
                .andExpect(jsonPath("$.message").value(containsString("MISSING_INFORMATION")))
                .andExpect(jsonPath("$.message").value(containsString("VALIDATED")))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()));

        DocumentRequest stored = requestRepository.store.get(requestId);
        assertThat(stored.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(documentRepository.store).isEmpty();
    }

    @Test
    void e5_generate_without_template_returns_500_and_fails_request() throws Exception {
        templateRepository.template = Optional.empty();
        UUID requestId = createRequest(validBody());

        mockMvc.perform(post("/api/v1/requests/" + requestId + "/generate"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(content().string(not(containsString("java."))));

        DocumentRequest stored = requestRepository.store.get(requestId);
        assertThat(stored.getStatus()).isEqualTo(RequestStatus.FAILED);

        mockMvc.perform(get("/api/v1/requests/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void e5_generate_on_unknown_request_returns_404() throws Exception {
        mockMvc.perform(post("/api/v1/requests/" + UUID.randomUUID() + "/generate"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // E6 — GET /api/v1/requests/{requestId}/documents/{documentId}
    // ------------------------------------------------------------------

    @Test
    void e6_download_returns_docx_bytes_with_headers() throws Exception {
        UUID requestId = createRequest(validBody());
        UUID documentId = generateDocument(requestId);

        mockMvc.perform(get("/api/v1/requests/" + requestId + "/documents/" + documentId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(DOCX_MIME_TYPE))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"attestation-concordance-" + requestId + ".docx\""))
                .andExpect(content().bytes("Attestation {{prenom}}".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void e6_unknown_document_returns_404() throws Exception {
        UUID requestId = createRequest(validBody());

        mockMvc.perform(get("/api/v1/requests/" + requestId + "/documents/"
                        + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void e6_unknown_request_returns_404() throws Exception {
        UUID requestId = createRequest(validBody());
        UUID documentId = generateDocument(requestId);

        mockMvc.perform(get("/api/v1/requests/" + UUID.randomUUID() + "/documents/"
                        + documentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // E7 — GET /api/v1/health
    // ------------------------------------------------------------------

    @Test
    void e7_health_returns_200_up() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void e7_health_returns_503_down_when_database_unavailable() throws Exception {
        MockMvc downMvc = buildMockMvc(
                new DocumentRequestController(requestService, documentGenerationService,
                        new RequestResponseMapper(new ValidationService(CLOCK),
                                documentGenerationService),
                        objectMapper),
                requestService, failingDataSource());

        downMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    // ------------------------------------------------------------------
    // X-Correlation-Id
    // ------------------------------------------------------------------

    @Test
    void correlation_id_is_generated_when_absent_and_exposed_in_errors() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        get("/api/v1/requests/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id",
                        matchesPattern("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
                .andReturn().getResponse();

        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
        assertThat(objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8))
                .get("correlationId").asText())
                .isEqualTo(response.getHeader("X-Correlation-Id"));
    }

    @Test
    void correlation_id_is_echoed_when_valid() throws Exception {
        mockMvc.perform(get("/api/v1/requests/" + UUID.randomUUID())
                        .header("X-Correlation-Id", "trace-abc-123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "trace-abc-123"))
                .andExpect(jsonPath("$.correlationId").value("trace-abc-123"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    private String createBodyForMissingDate() {
        return """
                {"documentType":"ATTESTATION_CONCORDANCE",
                 "data":{"prenom":"Maria","nom":"Gomes",
                         "nomIncorrect":"Maria Gomez","nomCorrect":"Maria Gomes"}}
                """;
    }

    private UUID createRequest(String body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(objectMapper.readTree(response).get("requestId").asText());
    }

    private UUID createMissingRequest() throws Exception {
        String response = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBodyForMissingDate()))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(objectMapper.readTree(response).get("requestId").asText());
    }

    private UUID createRejectedRequest() throws Exception {
        String response = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody().replace("Gomes", "Gomes@123")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(objectMapper.readTree(response).get("requestId").asText());
    }

    private UUID generateDocument(UUID requestId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/requests/" + requestId + "/generate"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(objectMapper.readTree(response).get("documentId").asText());
    }

    // ------------------------------------------------------------------
    // Ports en mémoire / doublons
    // ------------------------------------------------------------------

    private static final class InMemoryRequestRepository implements RequestRepository {

        private final Map<UUID, DocumentRequest> store = new LinkedHashMap<>();
        private int saveCount;

        @Override
        public DocumentRequest save(DocumentRequest request) {
            saveCount++;
            store.put(request.getRequestId(), request);
            return request;
        }

        @Override
        public Optional<DocumentRequest> findById(UUID requestId) {
            return Optional.ofNullable(store.get(requestId));
        }
    }

    private static final class InMemoryGeneratedDocumentRepository
            implements GeneratedDocumentRepository {

        private final Map<UUID, GeneratedDocument> store = new LinkedHashMap<>();

        @Override
        public GeneratedDocument save(GeneratedDocument document) {
            store.put(document.getDocumentId(), document);
            return document;
        }

        @Override
        public Optional<GeneratedDocument> findByIdAndRequestId(UUID documentId, UUID requestId) {
            GeneratedDocument document = store.get(documentId);
            return document != null && document.getRequestId().equals(requestId)
                    ? Optional.of(document)
                    : Optional.empty();
        }

        @Override
        public List<GeneratedDocument> findByRequestId(UUID requestId) {
            List<GeneratedDocument> documents = new ArrayList<>();
            for (GeneratedDocument document : store.values()) {
                if (document.getRequestId().equals(requestId)) {
                    documents.add(document);
                }
            }
            return documents;
        }

        @Override
        public void delete(UUID documentId) {
            store.remove(documentId);
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

        @Override
        public void delete(String storagePath) {
            files.remove(storagePath);
        }
    }

    private static final class FixedTemplateEngine implements TemplateEngine {

        @Override
        public byte[] merge(Template template, Map<String, String> templateVariables) {
            return "Attestation {{prenom}}".getBytes(StandardCharsets.UTF_8);
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

    private static DataSource failingDataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("database down");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("database down");
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("unsupported");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }
}
