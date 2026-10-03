package com.adgendoc.persistence;

import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.ports.RequestRepository;
import com.adgendoc.infrastructure.persistence.AuditLogEntity;
import com.adgendoc.infrastructure.persistence.AuditLogJpaRepository;
import com.adgendoc.infrastructure.persistence.DocumentRequestEntity;
import com.adgendoc.infrastructure.persistence.DocumentRequestJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>GATE PostgreSQL — phase F1</b> (exécuté SÉPARÉMENT du gate hors-ligne,
 * Docker requis) :
 *
 * <pre>
 * mvn -o -f backend/pom.xml test -Dtest=PostgresPersistenceIT
 * </pre>
 *
 * Preuves attendues : migration {@code V1__init.sql} exécutée (Flyway),
 * tables/constraints/index présents, {@code ddl-auto: validate} au
 * démarrage de l'application (compatibilité schéma ⇄ entités JPA), flux
 * Controller → Service → Persistence Adapter sur PostgreSQL réel, mapping
 * JSONB / {@code TEXT[]}, verrouillage optimiste, audit sans PII, FK réelles
 * de V1. Aucun mock sur les couches application/persistance.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostgresPersistenceIT {

    private static final String API = "/api/v1/requests";
    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";
    private static final Path REPOSITORY_TEMPLATE_DIR = Path.of("..", "templates");
    private static final String TEMPLATE_FILE = "attestation_concordance_v1.docx";

    /** Stockage des tests : jamais le stockage réel du développeur (§9). */
    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void documentStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.document.storage-path", () -> storageRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private DocumentRequestJpaRepository documentRequestJpaRepository;

    @Autowired
    private AuditLogJpaRepository auditLogJpaRepository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM generated_document");
        jdbcTemplate.update("DELETE FROM document_request");
        // template_registry NON touché : seed V2 à conserver tel quel.
    }

    // ------------------------------------------------------------------
    // 1 — Migration Flyway + schéma PostgreSQL
    // ------------------------------------------------------------------

    @Test
    void flyway_migrations_v1_and_v2_are_applied_successfully() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, description, success FROM flyway_schema_history"
                        + " ORDER BY installed_rank");
        assertThat(history).hasSizeGreaterThanOrEqualTo(2);
        assertThat(String.valueOf(history.get(0).get("version"))).isEqualTo("1");
        assertThat(Boolean.TRUE.equals(history.get(0).get("success"))).isTrue();
        assertThat(String.valueOf(history.get(1).get("version"))).isEqualTo("2");
        assertThat(Boolean.TRUE.equals(history.get(1).get("success"))).isTrue();
    }

    @Test
    void hibernate_ddl_auto_remains_validate() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("validate");
    }

    @Test
    void v1_tables_are_created() {
        for (String table : List.of("document_request", "generated_document",
                "template_registry", "audit_log")) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables"
                            + " WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            assertThat(count).as("table %s", table).isEqualTo(1);
        }
    }

    @Test
    void v1_constraints_are_present() {
        for (String constraint : List.of(
                "document_request_pkey",
                "ck_document_request_type",
                "ck_document_request_status",
                "generated_document_pkey",
                "fk_generated_document_request",
                "ck_generated_document_byte_size",
                "ck_generated_document_sha256",
                "uq_generated_document_request_sha256",
                "pk_template_registry",
                "uq_template_registry_checksum",
                "ck_template_registry_checksum",
                "audit_log_pkey",
                "fk_audit_log_request")) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_constraint WHERE conname = ?", Integer.class,
                    constraint);
            assertThat(count).as("constraint %s", constraint).isEqualTo(1);
        }
    }

    @Test
    void v1_indexes_are_present() {
        for (String index : List.of(
                "idx_document_request_status",
                "idx_document_request_correlation_id",
                "idx_generated_document_request_id",
                "idx_audit_log_request_id",
                "uq_template_registry_active_code")) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class,
                    index);
            assertThat(count).as("index %s", index).isEqualTo(1);
        }
    }

    @Test
    void v1_column_types_match_entities() {
        assertThat(columnType("document_request", "payload")).isEqualTo("jsonb");
        assertThat(columnType("document_request", "missing_fields")).isEqualTo("text[]");
        assertThat(columnType("document_request", "created_at"))
                .isEqualTo("timestamp with time zone");
        assertThat(columnType("document_request", "status"))
                .isEqualTo("character varying(30)");
        assertThat(columnType("audit_log", "details")).isEqualTo("jsonb");
    }

    // ------------------------------------------------------------------
    // 2 — Vertical slice sur PostgreSQL réel (HTTP → JPA → PG)
    // ------------------------------------------------------------------

    @Test
    void create_persists_on_postgres_with_jsonb_and_audit_without_pii() throws Exception {
        String response = mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(objectMapper.readTree(response)
                .get("requestId").asText());

        // Colonnes PostgreSQL réelles.
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM document_request WHERE request_id = ?", String.class,
                requestId);
        assertThat(status).isEqualTo("VALIDATED");
        String payloadText = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM document_request WHERE request_id = ?",
                String.class, requestId);
        assertThat(payloadText).contains("\"Maria\"").contains("1985-05-12");

        DocumentRequestEntity entity = documentRequestJpaRepository.findById(requestId)
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) entity.getPayload().get("data");
        assertThat(data.get("prenom")).isEqualTo("Maria");
        assertThat(entity.getMissingFields()).isEmpty();

        // Audit sans PII.
        String detailsText = jdbcTemplate.queryForObject(
                "SELECT details::text FROM audit_log WHERE request_id = ?",
                String.class, requestId);
        assertThat(detailsText).contains("nbMissingFields");
        assertThat(detailsText)
                .doesNotContain("Maria").doesNotContain("Gomes").doesNotContain("1985");
    }

    @Test
    void missing_information_persists_text_array_then_patch_normalizes_date() throws Exception {
        String response = mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"documentType":"ATTESTATION_CONCORDANCE",
                                 "data":{"prenom":"Maria","nom":"Gomes",
                                         "nomIncorrect":"Maria Gomez",
                                         "nomCorrect":"Maria Gomes"}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingFields[0]").value("dateNaissance"))
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(objectMapper.readTree(response)
                .get("requestId").asText());

        String missingText = jdbcTemplate.queryForObject(
                "SELECT missing_fields::text FROM document_request WHERE request_id = ?",
                String.class, requestId);
        assertThat(missingText).isEqualTo("{dateNaissance,lieuNaissance}");

        mockMvc.perform(patch(API + "/" + requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"data":{"dateNaissance":"12/05/1985",
                                         "lieuNaissance":"Bissau"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.data.dateNaissance").value("1985-05-12"));

        String storedDate = jdbcTemplate.queryForObject(
                "SELECT payload->'data'->>'dateNaissance' FROM document_request"
                        + " WHERE request_id = ?",
                String.class, requestId);
        assertThat(storedDate).isEqualTo("1985-05-12");
        String missingAfter = jdbcTemplate.queryForObject(
                "SELECT missing_fields::text FROM document_request WHERE request_id = ?",
                String.class, requestId);
        assertThat(missingAfter).isEqualTo("{}");
    }

    @Test
    void rejected_request_persists_rejected_status() throws Exception {
        String body = validBody().replace("1985-05-12", "abc");
        String response = mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(objectMapper.readTree(response)
                .get("requestId").asText());

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM document_request WHERE request_id = ?", String.class,
                requestId);
        assertThat(status).isEqualTo("REJECTED");
    }

    // ------------------------------------------------------------------
    // 3 — Verrouillage optimiste sur PostgreSQL
    // ------------------------------------------------------------------

    @Test
    void stale_write_is_rejected_by_optimistic_locking_on_postgres() throws Exception {
        String response = mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(objectMapper.readTree(response)
                .get("requestId").asText());

        DocumentRequest first = requestRepository.findById(requestId).orElseThrow();
        DocumentRequest second = requestRepository.findById(requestId).orElseThrow();

        first.mergeData(Map.of("motif", "Premiere ecriture"), Instant.now(clock));
        requestRepository.save(first);

        assertThatThrownBy(() -> {
            second.mergeData(Map.of("motif", "Ecriture concurrente"), Instant.now(clock));
            requestRepository.save(second);
        }).isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------
    // 4 — Contraintes FK réelles de V1 (impossibles sur H2 : pas de FK)
    // ------------------------------------------------------------------

    @Test
    void audit_log_foreign_key_violation_raises_data_access_exception() {
        AuditLogEntity orphan = new AuditLogEntity();
        orphan.setRequestId(UUID.randomUUID()); // aucune document_request correspondante
        orphan.setAction("ORPHAN_TEST");
        orphan.setActor("system");
        orphan.setCreatedAt(Instant.now(clock));
        orphan.setDetails(Map.of());

        assertThatThrownBy(() -> auditLogJpaRepository.saveAndFlush(orphan))
                .isInstanceOf(DataAccessException.class);
    }

    // ------------------------------------------------------------------
    // 5 — V2 : seed présent, checksum = SHA-256 RÉEL du template versionné
    //     (R-04) — le template reste un template de dev NON APPROUVÉ (F1/F2)
    // ------------------------------------------------------------------

    @Test
    void v2_seed_checksum_equals_real_sha256_of_versioned_template()
            throws IOException {
        String checksum = jdbcTemplate.queryForObject(
                "SELECT checksum FROM template_registry WHERE code = ? AND version = ?",
                String.class, DOCUMENT_TYPE, "1.0");
        byte[] templateBytes = Files.readAllBytes(
                REPOSITORY_TEMPLATE_DIR.resolve(TEMPLATE_FILE));
        assertThat(checksum).isEqualTo(sha256Hex(templateBytes));
        Boolean active = jdbcTemplate.queryForObject(
                "SELECT active FROM template_registry WHERE code = ? AND version = ?",
                Boolean.class, DOCUMENT_TYPE, "1.0");
        assertThat(active).isTrue();
    }

    // ------------------------------------------------------------------
    // 6 — Génération documentaire sur PostgreSQL réel : template réel,
    //     checksum réel, PoiTemplateEngine, FileSystemStorageAdapter
    //     (@TempDir), generated_document persisté, récupération + POI
    // ------------------------------------------------------------------

    @Test
    void document_generation_runs_end_to_end_on_postgres() throws Exception {
        String createResponse = mockMvc.perform(post(API)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(objectMapper.readTree(createResponse)
                .get("requestId").asText());

        String generateResponse = mockMvc.perform(post(API + "/" + requestId + "/generate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andReturn().getResponse().getContentAsString();
        UUID documentId = UUID.fromString(objectMapper.readTree(generateResponse)
                .get("documentId").asText());

        String storedSha = jdbcTemplate.queryForObject(
                "SELECT sha256 FROM generated_document WHERE document_id = ?",
                String.class, documentId);
        assertThat(storedSha).matches("[0-9a-f]{64}");

        Path storedFile = storageRoot.resolve(requestId.toString())
                .resolve(documentId + ".docx");
        assertThat(storedFile).exists();

        byte[] downloaded = mockMvc.perform(get(API + "/" + requestId
                        + "/documents/" + documentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(sha256Hex(downloaded)).isEqualTo(storedSha);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(downloaded))) {
            StringBuilder text = new StringBuilder();
            document.getParagraphs().forEach(p -> text.append(p.getText()).append('\n'));
            assertThat(text.toString())
                    .contains("Maria").contains("Gomes")
                    .contains(requestId.toString())
                    .contains("NON APPROUVÉ POUR PRODUCTION");
            assertThat(text.toString()).doesNotContain("{{");
        }
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String columnType(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT format_type(a.atttypid, a.atttypmod)"
                        + " FROM pg_attribute a"
                        + " WHERE a.attrelid = ?::regclass AND a.attname = ?"
                        + " AND a.attnum > 0 AND NOT a.attisdropped",
                String.class, table, column);
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
}
