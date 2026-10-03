package com.adgendoc.api;

import com.adgendoc.infrastructure.docx.PoiTemplateEngine;
import com.adgendoc.infrastructure.persistence.TemplateRegistryJpaRepository;
import com.adgendoc.infrastructure.persistence.TemplateRegistryEntity;
import com.adgendoc.infrastructure.storage.FileSystemStorageAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase F2 — <b>test d'intégration documentaire sans aucun mock</b> :
 * requête persistée (H2 réel) → template DOCX réel versionné → checksum
 * SHA-256 réel vérifié par {@link PoiTemplateEngine} → fusion →
 * {@link FileSystemStorageAdapter} sur répertoire temporaire → ligne
 * {@code generated_document} persistée → récupération HTTP → réouverture
 * du résultat avec Apache POI et valeurs fusionnées vérifiées.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:adgendoc_docflow;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentGenerationFlowTest {

    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";
    private static final Path REPOSITORY_TEMPLATE_DIR = Path.of("..", "templates");
    private static final String TEMPLATE_FILE = "attestation_concordance_v1.docx";
    private static final String MARKER = "NON APPROUVÉ POUR PRODUCTION";

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
    private TemplateRegistryJpaRepository templateRegistryJpaRepository;

    @BeforeEach
    void seedRealTemplate() {
        templateRegistryJpaRepository.deleteAll();
        TemplateRegistryEntity entity = new TemplateRegistryEntity();
        entity.setCode(DOCUMENT_TYPE);
        entity.setVersion("1.0");
        entity.setFilePath(TEMPLATE_FILE);
        entity.setChecksum(sha256Hex(realTemplateBytes()));
        entity.setActive(true);
        entity.setCreatedAt(Instant.now());
        templateRegistryJpaRepository.save(entity);
    }

    @Test
    void request_to_docx_flow_runs_end_to_end_without_any_mock() throws Exception {
        // Préuve checksum : registry == fichier réel.
        byte[] templateBytes = realTemplateBytes();
        String registryChecksum = jdbcTemplate.queryForObject(
                "SELECT checksum FROM template_registry WHERE code = ?", String.class,
                DOCUMENT_TYPE);
        assertThat(registryChecksum).isEqualTo(sha256Hex(templateBytes));

        // E1 — requête persistée et VALIDATED.
        String createResponse = mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(objectMapper.readTree(createResponse)
                .get("requestId").asText());

        // E5 — génération réelle (POI + filesystem, beans Spring sans mock).
        String generateResponse = mockMvc.perform(
                        post("/api/v1/requests/" + requestId + "/generate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andReturn().getResponse().getContentAsString();
        UUID documentId = UUID.fromString(objectMapper.readTree(generateResponse)
                .get("documentId").asText());

        // Fichier physique : <storage>/<requestId>/<documentId>.docx.
        Path storedFile = storageRoot.resolve(requestId.toString())
                .resolve(documentId + ".docx");
        assertThat(storedFile).exists();

        // Métadonnées generated_document.
        String storedSha = jdbcTemplate.queryForObject(
                "SELECT sha256 FROM generated_document WHERE document_id = ?",
                String.class, documentId);
        long byteSize = jdbcTemplate.queryForObject(
                "SELECT byte_size FROM generated_document WHERE document_id = ?",
                Long.class, documentId);
        String mimeType = jdbcTemplate.queryForObject(
                "SELECT mime_type FROM generated_document WHERE document_id = ?",
                String.class, documentId);
        assertThat(byteSize).isPositive();
        assertThat(storedSha).matches("[0-9a-f]{64}");
        assertThat(mimeType).isEqualTo(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        // E6 — récupération HTTP puis réouverture avec Apache POI.
        byte[] downloaded = mockMvc.perform(get("/api/v1/requests/" + requestId
                        + "/documents/" + documentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(downloaded).isNotEmpty();
        assertThat(sha256Hex(downloaded)).isEqualTo(storedSha);
        assertThat(downloaded).isEqualTo(Files.readAllBytes(storedFile));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(downloaded))) {
            StringBuilder text = new StringBuilder();
            document.getParagraphs().forEach(p -> text.append(p.getText()).append('\n'));
            assertThat(text.toString())
                    .contains("Maria").contains("Gomes").contains("Bissau")
                    .contains(requestId.toString())
                    .contains(MARKER);
            assertThat(text.toString()).doesNotContain("{{").doesNotContain("}}");
        }
    }

    // ------------------------------------------------------------------

    private byte[] realTemplateBytes() {
        try {
            return Files.readAllBytes(REPOSITORY_TEMPLATE_DIR.resolve(TEMPLATE_FILE));
        } catch (IOException exception) {
            throw new AssertionError("Template reel absent : "
                    + REPOSITORY_TEMPLATE_DIR.resolve(TEMPLATE_FILE).toAbsolutePath(),
                    exception);
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
