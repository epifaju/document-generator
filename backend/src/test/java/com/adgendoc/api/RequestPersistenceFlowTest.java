package com.adgendoc.api;

import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.RequestStatus;
import com.adgendoc.domain.ports.RequestRepository;
import com.adgendoc.infrastructure.persistence.AuditLogEntity;
import com.adgendoc.infrastructure.persistence.AuditLogJpaRepository;
import com.adgendoc.infrastructure.persistence.DocumentRequestEntity;
import com.adgendoc.infrastructure.persistence.DocumentRequestJpaRepository;
import com.adgendoc.infrastructure.persistence.GeneratedDocumentJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase F1 — test d'intégration SANS MOCK sur les couches application et
 * persistance : Controller → Service → Persistence Adapter → Spring Data JPA
 * → base réelle (H2 hors-ligne). Preuves : transitions persistées, mapping
 * JSONB du payload, {@code missingFields} ordonnés, verrouillage optimiste,
 * audit sans PII, erreurs base classifiées. Le gate PostgreSQL séparé rejoue
 * les mêmes scénarios sur {@code V1__init.sql}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestPersistenceFlowTest {

    private static final String API = "/api/v1/requests";
    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DocumentRequestJpaRepository documentRequestJpaRepository;

    @Autowired
    private AuditLogJpaRepository auditLogJpaRepository;

    @Autowired
    private GeneratedDocumentJpaRepository generatedDocumentJpaRepository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanTables() {
        auditLogJpaRepository.deleteAll();
        generatedDocumentJpaRepository.deleteAll();
        documentRequestJpaRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // E1 → persistance JSONB + audit sans PII
    // ------------------------------------------------------------------

    @Test
    void e1_valid_create_persists_jsonb_payload_status_and_audit_without_pii() throws Exception {
        UUID requestId = createValidRequest();

        DocumentRequestEntity entity = documentRequestJpaRepository.findById(requestId)
                .orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(entity.getDocumentType()).isEqualTo(DOCUMENT_TYPE);
        assertThat(entity.getMissingFields()).isEmpty();
        assertThat(entity.getVersion()).isNotNull();
        assertThat(entity.getCorrelationId()).isNotBlank();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();

        // Roundtrip JSONB : payload.enveloppe + data normalisés.
        assertThat(entity.getPayload()).containsKey("documentType");
        assertThat(entity.getPayload().get("documentType")).isEqualTo(DOCUMENT_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) entity.getPayload().get("data");
        assertThat(data.get("prenom")).isEqualTo("Maria");
        assertThat(data.get("nom")).isEqualTo("Gomes");
        assertThat(data.get("dateNaissance")).isEqualTo("1985-05-12");
        assertThat(data.get("lieuNaissance")).isEqualTo("Bissau");
        @SuppressWarnings("unchecked")
        Map<String, Object> extraction = (Map<String, Object>) entity.getPayload()
                .get("extraction");
        assertThat(extraction).containsKeys("confidence", "modelId", "promptVersion");
        assertThat(((Number) extraction.get("confidence")).doubleValue()).isEqualTo(0.97);

        // Audit : une seule ligne REQUEST_CREATED, détails techniques uniquement.
        List<AuditLogEntity> audit = auditLogJpaRepository.findAll();
        assertThat(audit).hasSize(1);
        AuditLogEntity entry = audit.get(0);
        assertThat(entry.getAction()).isEqualTo("REQUEST_CREATED");
        assertThat(entry.getActor()).isEqualTo("system");
        assertThat(entry.getRequestId()).isEqualTo(requestId);
        assertThat(entry.getCreatedAt()).isNotNull();
        assertThat(entry.getDetails()).containsOnlyKeys("documentType", "status",
                "nbMissingFields");
        String detailsText = String.valueOf(entry.getDetails());
        assertThat(detailsText)
                .doesNotContain("Maria").doesNotContain("Gomes").doesNotContain("1985")
                .doesNotContain("Bissau");
    }

    // ------------------------------------------------------------------
    // 422 → missingFields ordonnés persistés (V1 TEXT[])
    // ------------------------------------------------------------------

    @Test
    void e1_missing_information_persists_missing_fields_in_canonical_order() throws Exception {
        UUID requestId = createMissingRequest();

        DocumentRequestEntity entity = documentRequestJpaRepository.findById(requestId)
                .orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(RequestStatus.MISSING_INFORMATION);
        assertThat(entity.getMissingFields())
                .containsExactly("dateNaissance", "lieuNaissance");
        assertThat(entity.getPayload().get("data")).isInstanceOf(Map.class);
    }

    // ------------------------------------------------------------------
    // E3 PATCH → normalisation dd/MM/yyyy → ISO (F-07/R-06) + transitions
    // ------------------------------------------------------------------

    @Test
    void patch_normalizes_french_date_to_iso_clears_missing_and_increments_version()
            throws Exception {
        UUID requestId = createMissingRequest();
        int versionBefore = documentRequestJpaRepository.findById(requestId).orElseThrow()
                .getVersion();

        mockMvc.perform(patch(API + "/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"data":{"dateNaissance":"12/05/1985",
                                         "lieuNaissance":"Bissau"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.missingFields").isEmpty())
                .andExpect(jsonPath("$.data.dateNaissance").value("1985-05-12"));

        DocumentRequestEntity entity = documentRequestJpaRepository.findById(requestId)
                .orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(RequestStatus.VALIDATED);
        assertThat(entity.getMissingFields()).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) entity.getPayload().get("data");
        assertThat(data.get("dateNaissance")).isEqualTo("1985-05-12");
        assertThat(entity.getVersion()).isGreaterThan(versionBefore);
        assertThat(auditLogJpaRepository.findAll())
                .extracting(AuditLogEntity::getAction)
                .contains("REQUEST_PATCHED");
    }

    // ------------------------------------------------------------------
    // E4 → re-validation d'un état persisté
    // ------------------------------------------------------------------

    @Test
    void e4_validate_recomputes_from_persisted_state() throws Exception {
        UUID requestId = createMissingRequest();

        mockMvc.perform(post(API + "/" + requestId + "/validate"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingFields[0]").value("dateNaissance"));

        mockMvc.perform(patch(API + "/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dateNaissance":"12/05/1985","lieuNaissance":"Bissau"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post(API + "/" + requestId + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    // ------------------------------------------------------------------
    // E1 rejeté → statut REJECTED persisté, fieldErrors recalculés (E2)
    // ------------------------------------------------------------------

    @Test
    void e1_rejected_persists_status_and_field_errors_are_recomputed_on_read()
            throws Exception {
        String body = validBody().replace("1985-05-12", "abc");
        String response = mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("ERR_DATE_FORMAT_INVALIDE"))
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(objectMapper.readTree(response)
                .get("requestId").asText());

        DocumentRequestEntity entity = documentRequestJpaRepository.findById(requestId)
                .orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(RequestStatus.REJECTED);

        mockMvc.perform(get(API + "/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.fieldErrors[0].code")
                        .value("ERR_DATE_FORMAT_INVALIDE"));
    }

    // ------------------------------------------------------------------
    // E2 → lecture du persisté
    // ------------------------------------------------------------------

    @Test
    void e2_returns_persisted_state_from_database() throws Exception {
        UUID requestId = createValidRequest();

        mockMvc.perform(get(API + "/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.referenceDemande").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.data.nom").value("Gomes"))
                .andExpect(jsonPath("$.missingFields").isEmpty())
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    // ------------------------------------------------------------------
    // Verrouillage optimiste (V1 version + @Version)
    // ------------------------------------------------------------------

    @Test
    void stale_write_is_rejected_by_optimistic_locking() {
        UUID requestId = createValidRequestQuietly();
        DocumentRequest first = requestRepository.findById(requestId).orElseThrow();
        DocumentRequest second = requestRepository.findById(requestId).orElseThrow();

        first.mergeData(Map.of("motif", "Premiere ecriture"), Instant.now(clock));
        requestRepository.save(first);

        assertThatThrownBy(() -> {
            second.mergeData(Map.of("motif", "Ecriture concurrente"), Instant.now(clock));
            requestRepository.save(second);
        }).isInstanceOf(DataAccessException.class);

        DocumentRequestEntity persisted = documentRequestJpaRepository.findById(requestId)
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) persisted.getPayload().get("data");
        assertThat(data.get("motif")).isEqualTo("Premiere ecriture");
    }

    // ------------------------------------------------------------------
    // E5 sans template réel (F1) → échec classifié + transition FAILED
    // ------------------------------------------------------------------

    @Test
    void e5_without_template_marks_request_failed_and_returns_template_not_found()
            throws Exception {
        UUID requestId = createValidRequest();

        mockMvc.perform(post(API + "/" + requestId + "/generate"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));

        DocumentRequestEntity entity = documentRequestJpaRepository.findById(requestId)
                .orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(auditLogJpaRepository.findAll())
                .extracting(AuditLogEntity::getAction)
                .contains("DOCUMENT_GENERATION_FAILED");
    }

    // ------------------------------------------------------------------
    // E7 → santé sur la vraie DataSource
    // ------------------------------------------------------------------

    @Test
    void e7_health_is_up_on_real_datasource() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ------------------------------------------------------------------
    // Erreur base → DataAccessException (classifiée DATABASE_ERROR par
    // GlobalExceptionHandler, testée dans DatabaseErrorHandlerTest)
    // ------------------------------------------------------------------

    @Test
    void repository_constraint_violation_raises_data_access_exception() {
        AuditLogEntity invalid = new AuditLogEntity();
        invalid.setRequestId(UUID.randomUUID());
        invalid.setAction(null); // NOT NULL violation (V1 / create-drop)
        invalid.setActor("system");
        invalid.setCreatedAt(Instant.now(clock));
        invalid.setDetails(Map.of());

        assertThatThrownBy(() -> auditLogJpaRepository.saveAndFlush(invalid))
                .isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private UUID createValidRequest() throws Exception {
        String response = mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("requestId").asText());
    }

    private UUID createValidRequestQuietly() {
        try {
            return createValidRequest();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID createMissingRequest() throws Exception {
        String response = mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingFields[0]").value("dateNaissance"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("requestId").asText());
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

    private String missingBody() {
        return """
                {"documentType":"ATTESTATION_CONCORDANCE",
                 "data":{"prenom":"Maria","nom":"Gomes",
                         "nomIncorrect":"Maria Gomez","nomCorrect":"Maria Gomes"}}
                """;
    }
}
