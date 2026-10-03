package com.adgendoc.application;

import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.GeneratedDocument;
import com.adgendoc.domain.RequestStatus;
import com.adgendoc.domain.Template;
import com.adgendoc.domain.exceptions.DocumentGenerationException;
import com.adgendoc.domain.exceptions.DocumentNotFoundException;
import com.adgendoc.domain.exceptions.InvalidStatusException;
import com.adgendoc.domain.exceptions.RequestNotFoundException;
import com.adgendoc.domain.exceptions.TemplateNotFoundException;
import com.adgendoc.domain.ports.AuditPort;
import com.adgendoc.domain.ports.DocumentStorage;
import com.adgendoc.domain.ports.GeneratedDocumentRepository;
import com.adgendoc.domain.ports.RequestRepository;
import com.adgendoc.domain.ports.TemplateEngine;
import com.adgendoc.domain.ports.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentGenerationServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private static final String DOCUMENT_TYPE = "ATTESTATION_CONCORDANCE";
    private static final String TEMPLATE_FILE = "attestation_concordance_v1.docx";
    private static final String DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String TEMPLATE_TEXT = """
            Attestation de concordance
            {{prenom}} {{nom}} ne(e) le {{date_naissance}} a {{lieu_naissance}}
            forme erronée {{nom_incorrect}} forme correcte {{nom_correct}}
            sexe {{sexe}} nationalite {{nationalite}}
            référence source {{document_source_reference}} langue {{langue_document}}
            demandeur {{demandeur}} motif {{motif}}
            référence demande {{reference_demande}} du {{date_generation}}
            """;

    @TempDir
    Path templateDirectory;

    private final InMemoryRequestRepository requestRepository = new InMemoryRequestRepository();
    private final InMemoryGeneratedDocumentRepository documentRepository =
            new InMemoryGeneratedDocumentRepository();
    private final RecordingTemplateRepository templateRepository =
            new RecordingTemplateRepository();
    private final InMemoryDocumentStorage documentStorage = new InMemoryDocumentStorage();
    private final RecordingAuditPort auditPort = new RecordingAuditPort();

    private DocumentGenerationService service;

    @BeforeEach
    void setUp() throws IOException {
        Path templateFile = templateDirectory.resolve(TEMPLATE_FILE);
        Files.writeString(templateFile, TEMPLATE_TEXT, StandardCharsets.UTF_8);
        String checksum = sha256Hex(Files.readAllBytes(templateFile));
        templateRepository.template =
                Optional.of(new Template(DOCUMENT_TYPE, "1.0", TEMPLATE_FILE, checksum, true));
        service = new DocumentGenerationService(requestRepository, documentRepository,
                templateRepository, new ChecksumVerifyingTemplateEngine(templateDirectory),
                documentStorage, auditPort, CLOCK);
    }

    @Test
    void generate_looks_up_active_template_by_document_type() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        service.generate(request.getRequestId());

        assertThat(templateRepository.lastCode).isEqualTo(DOCUMENT_TYPE);
    }

    @Test
    void generate_without_template_fails_the_request() {
        templateRepository.template = Optional.empty();
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        assertThatThrownBy(() -> service.generate(request.getRequestId()))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEMPLATE_NOT_FOUND);

        assertThat(requestRepository.store.get(request.getRequestId()).getStatus())
                .isEqualTo(RequestStatus.FAILED);
        assertThat(auditPort.actions).contains("DOCUMENT_GENERATION_FAILED");
    }

    @Test
    void generate_with_valid_checksum_succeeds() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        GeneratedDocument document = service.generate(request.getRequestId());

        assertThat(document.getByteSize()).isPositive();
        assertThat(documentRepository.findByRequestId(request.getRequestId()))
                .containsExactly(document);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.GENERATED);
    }

    @Test
    void generate_with_invalid_checksum_fails_the_request() {
        templateRepository.template = Optional.of(
                new Template(DOCUMENT_TYPE, "1.0", TEMPLATE_FILE, "0".repeat(64), true));
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        assertThatThrownBy(() -> service.generate(request.getRequestId()))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.TEMPLATE_NOT_FOUND);

        assertThat(requestRepository.store.get(request.getRequestId()).getStatus())
                .isEqualTo(RequestStatus.FAILED);
        assertThat(auditPort.actions).containsExactly("DOCUMENT_GENERATION_FAILED");
    }

    @Test
    void generate_after_failed_generation_succeeds_once_revalidated() throws IOException {
        templateRepository.template = Optional.empty();
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        assertThatThrownBy(() -> service.generate(request.getRequestId()))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEMPLATE_NOT_FOUND);
        assertThat(requestRepository.store.get(request.getRequestId()).getStatus())
                .isEqualTo(RequestStatus.FAILED);

        // Reprise S15 : FAILED -> VALIDATED puis E5 -> GENERATED.
        templateRepository.template = Optional.of(
                new Template(DOCUMENT_TYPE, "1.0", TEMPLATE_FILE,
                        sha256Hex(Files.readAllBytes(templateDirectory.resolve(TEMPLATE_FILE))),
                        true));
        request.transitionTo(RequestStatus.VALIDATED, List.of(), Instant.now(CLOCK));

        GeneratedDocument document = service.generate(request.getRequestId());

        assertThat(document.getByteSize()).isPositive();
        assertThat(documentRepository.findByRequestId(request.getRequestId()))
                .containsExactly(document);
        assertThat(requestRepository.store.get(request.getRequestId()).getStatus())
                .isEqualTo(RequestStatus.GENERATED);
    }

    @Test
    void generate_merges_template_variables_without_unresolved_placeholder() {

        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        GeneratedDocument document = service.generate(request.getRequestId());

        String merged = new String(documentStorage.files.get(document.getStoragePath()),
                StandardCharsets.UTF_8);
        assertThat(merged).contains("Maria").contains("Gomes").contains("Bissau");
        assertThat(merged).contains(request.getRequestId().toString()).contains("2026-06-15");
        assertThat(merged).doesNotContain("{{");
    }

    @Test
    void generate_produces_docx_bytes_status_and_lowercase_sha256() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        GeneratedDocument document = service.generate(request.getRequestId());

        byte[] storedBytes = documentStorage.files.get(document.getStoragePath());
        assertThat(storedBytes).isNotEmpty();
        assertThat(document.getByteSize()).isEqualTo(storedBytes.length);
        assertThat(document.getSha256()).matches("[0-9a-f]{64}");
        assertThat(document.getSha256()).isEqualTo(sha256Hex(storedBytes));
        assertThat(document.getMimeType()).isEqualTo(DOCX_MIME_TYPE);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.GENERATED);
    }

    @Test
    void generate_stores_file_under_request_and_document_ids() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        GeneratedDocument document = service.generate(request.getRequestId());

        assertThat(documentStorage.lastRequestId).isEqualTo(request.getRequestId());
        assertThat(documentStorage.lastDocumentId).isEqualTo(document.getDocumentId());
        assertThat(document.getStoragePath())
                .isEqualTo(request.getRequestId() + "/" + document.getDocumentId() + ".docx");
        assertThat(documentStorage.files).containsKey(document.getStoragePath());
    }

    @Test
    void get_document_returns_stored_bytes() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);
        GeneratedDocument document = service.generate(request.getRequestId());

        DocumentContent content = service.getDocument(request.getRequestId(),
                document.getDocumentId());

        assertThat(content.metadata()).isSameAs(document);
        assertThat(content.bytes())
                .isEqualTo(documentStorage.files.get(document.getStoragePath()));
    }

    @Test
    void get_unknown_document_throws_not_found() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);
        UUID unknownDocumentId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getDocument(request.getRequestId(), unknownDocumentId))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    void generate_unknown_request_throws_not_found_without_side_effects() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(() -> service.generate(unknownId))
                .isInstanceOf(RequestNotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REQUEST_NOT_FOUND)
                .hasFieldOrPropertyWithValue("requestId", unknownId);

        assertThat(templateRepository.lastCode).isNull();
        assertThat(documentRepository.findByRequestId(unknownId)).isEmpty();
        assertThat(auditPort.actions).isEmpty();
    }

    @Test
    void generation_audit_details_contain_no_personal_data() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        service.generate(request.getRequestId());

        assertThat(auditPort.actions).containsExactly("DOCUMENT_GENERATED");
        assertThat(auditPort.details).hasSize(1);
        Map<String, Object> details = auditPort.details.get(0);
        assertThat(details.keySet()).containsExactlyInAnyOrder("documentId", "byteSize");
        assertThat(details.toString())
                .doesNotContain("Maria")
                .doesNotContain("Gomes")
                .doesNotContain("Bissau");
    }

    @Test
    void failed_generation_audit_details_contain_no_personal_data() {
        templateRepository.template = Optional.empty();
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        assertThatThrownBy(() -> service.generate(request.getRequestId()))
                .isInstanceOf(TemplateNotFoundException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEMPLATE_NOT_FOUND);

        assertThat(auditPort.actions).containsExactly("DOCUMENT_GENERATION_FAILED");
        assertThat(auditPort.details).hasSize(1);
        Map<String, Object> details = auditPort.details.get(0);
        assertThat(details.keySet())
                .containsExactlyInAnyOrder("documentType", "status", "errorCode");
        assertThat(details.toString())
                .doesNotContain("Maria")
                .doesNotContain("Gomes")
                .doesNotContain("Bissau");
    }

    @Test
    void get_document_with_non_docx_storage_path_is_refused() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);
        GeneratedDocument document = new GeneratedDocument(UUID.randomUUID(),
                request.getRequestId(), "payload.txt", DOCX_MIME_TYPE, 10,
                "0".repeat(64), Instant.now(CLOCK));
        documentRepository.save(document);

        assertThatThrownBy(() -> service.getDocument(request.getRequestId(),
                document.getDocumentId()))
                .isInstanceOf(DocumentGenerationException.class);
    }

    @Test
    void generate_on_missing_information_is_refused() {
        DocumentRequest request = storedRequest(RequestStatus.MISSING_INFORMATION);

        assertThatThrownBy(() -> service.generate(request.getRequestId()))
                .isInstanceOf(InvalidStatusException.class);

        assertThat(templateRepository.lastCode).isNull();
        assertThat(auditPort.actions).isEmpty();
    }

    @Test
    void list_documents_returns_documents_generated_for_the_request() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);
        service.generate(request.getRequestId());

        List<GeneratedDocument> documents = service.listDocuments(request.getRequestId());

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getRequestId()).isEqualTo(request.getRequestId());
    }

    @Test
    void list_documents_is_empty_when_nothing_generated() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);

        assertThat(service.listDocuments(request.getRequestId())).isEmpty();
    }

    // ------------------------------------------------------------------
    // F2 — stratégie de compensation (aucune atomicité DB/filesystem)
    // ------------------------------------------------------------------

    @Test
    void generate_storage_failure_marks_failed_and_persists_nothing() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);
        DocumentGenerationService failing = new DocumentGenerationService(
                requestRepository, documentRepository, templateRepository,
                new ChecksumVerifyingTemplateEngine(templateDirectory),
                new FailingDocumentStorage(), auditPort, CLOCK);

        assertThatThrownBy(() -> failing.generate(request.getRequestId()))
                .isInstanceOf(DocumentGenerationException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.DOCUMENT_GENERATION_ERROR);

        assertThat(documentRepository.findByRequestId(request.getRequestId())).isEmpty();
        assertThat(requestRepository.store.get(request.getRequestId()).getStatus())
                .isEqualTo(RequestStatus.FAILED);
        assertThat(auditPort.actions).contains("DOCUMENT_GENERATION_FAILED");
    }

    @Test
    void generate_insert_failure_deletes_the_stored_file() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);
        FailingSaveDocumentRepository failingRepository =
                new FailingSaveDocumentRepository();
        DocumentGenerationService failing = new DocumentGenerationService(
                requestRepository, failingRepository, templateRepository,
                new ChecksumVerifyingTemplateEngine(templateDirectory),
                documentStorage, auditPort, CLOCK);

        assertThatThrownBy(() -> failing.generate(request.getRequestId()))
                .isInstanceOf(DocumentGenerationException.class);

        // Fichier écrit mais INSERT en échec ⇒ compensation : plus de fichier.
        assertThat(documentStorage.files).isEmpty();
        assertThat(failingRepository.saved).isEmpty();
        assertThat(requestRepository.store.get(request.getRequestId()).getStatus())
                .isEqualTo(RequestStatus.FAILED);
    }

    @Test
    void generate_transition_failure_compensates_row_and_file() {
        DocumentRequest request = storedRequest(RequestStatus.VALIDATED);
        FailingOnceRequestRepository failingRequests =
                new FailingOnceRequestRepository(requestRepository);
        failingRequests.failNextSave = true;
        DocumentGenerationService failing = new DocumentGenerationService(
                failingRequests, documentRepository, templateRepository,
                new ChecksumVerifyingTemplateEngine(templateDirectory),
                documentStorage, auditPort, CLOCK);

        assertThatThrownBy(() -> failing.generate(request.getRequestId()))
                .isInstanceOf(DocumentGenerationException.class);

        // Ligne générée ET fichier supprimés (compensation best-effort) ;
        // l'état status n'est pas revendiqué ici : la transition GENERATED
        // a échoué côté persistance (DB inchangée = VALIDATED en réel).
        assertThat(documentRepository.findByRequestId(request.getRequestId())).isEmpty();
        assertThat(documentStorage.files).isEmpty();
        assertThat(auditPort.actions).contains("DOCUMENT_GENERATION_FAILED");
    }

    private static final class FailingDocumentStorage implements DocumentStorage {

        @Override
        public String store(UUID requestId, UUID documentId, byte[] content) {
            throw new DocumentGenerationException(
                    "Echec d'ecriture simule (test F2).");
        }

        @Override
        public byte[] read(String storagePath) {
            throw new IllegalStateException("Absent");
        }

        @Override
        public void delete(String storagePath) {
            // rien stocké
        }
    }

    private static final class FailingSaveDocumentRepository
            implements GeneratedDocumentRepository {

        private final List<GeneratedDocument> saved = new ArrayList<>();

        @Override
        public GeneratedDocument save(GeneratedDocument document) {
            throw new DocumentGenerationException("Echec INSERT simule (test F2).");
        }

        @Override
        public Optional<GeneratedDocument> findByIdAndRequestId(UUID documentId,
                                                                UUID requestId) {
            return Optional.empty();
        }

        @Override
        public List<GeneratedDocument> findByRequestId(UUID requestId) {
            return List.copyOf(saved);
        }

        @Override
        public void delete(UUID documentId) {
            // aucune ligne créée
        }
    }

    private static final class FailingOnceRequestRepository implements RequestRepository {

        private final RequestRepository delegate;
        private boolean failNextSave;

        private FailingOnceRequestRepository(RequestRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public DocumentRequest save(DocumentRequest request) {
            if (failNextSave) {
                failNextSave = false;
                throw new IllegalStateException("Echec persistance transitoire (test F2).");
            }
            return delegate.save(request);
        }

        @Override
        public Optional<DocumentRequest> findById(UUID requestId) {
            return delegate.findById(requestId);
        }
    }

    private DocumentRequest storedRequest(RequestStatus status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentType", DOCUMENT_TYPE);
        payload.put("data", validData());
        DocumentRequest request = DocumentRequest.create(DOCUMENT_TYPE, payload, "corr-1",
                Instant.now(CLOCK));
        if (status == RequestStatus.VALIDATED) {
            request.transitionTo(RequestStatus.VALIDATED, List.of(), Instant.now(CLOCK));
        }
        if (status == RequestStatus.MISSING_INFORMATION) {
            request.transitionTo(RequestStatus.MISSING_INFORMATION, List.of("nomCorrect"),
                    Instant.now(CLOCK));
        }
        requestRepository.save(request);
        return request;
    }

    private Map<String, Object> validData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("prenom", "Maria");
        data.put("nom", "Gomes");
        data.put("dateNaissance", "1985-05-12");
        data.put("lieuNaissance", "Bissau");
        data.put("nomIncorrect", "Maria Gomez");
        data.put("nomCorrect", "Maria Gomes");
        data.put("langueDocument", "FR");
        return data;
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class ChecksumVerifyingTemplateEngine implements TemplateEngine {

        private final Path templateDirectory;

        private ChecksumVerifyingTemplateEngine(Path templateDirectory) {
            this.templateDirectory = templateDirectory;
        }

        @Override
        public byte[] merge(Template template, Map<String, String> templateVariables) {
            Path file = templateDirectory.resolve(template.getFilePath());
            byte[] content;
            try {
                content = Files.readAllBytes(file);
            } catch (IOException exception) {
                throw new TemplateNotFoundException(template.getCode(), exception);
            }
            if (!sha256Hex(content).equals(template.getChecksum())) {
                throw new TemplateNotFoundException(template.getCode());
            }
            String merged = new String(content, StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : templateVariables.entrySet()) {
                merged = merged.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            return merged.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class InMemoryRequestRepository implements RequestRepository {

        private final Map<UUID, DocumentRequest> store = new LinkedHashMap<>();

        @Override
        public DocumentRequest save(DocumentRequest request) {
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
        private String lastCode;

        @Override
        public Optional<Template> findActiveByCode(String code) {
            lastCode = code;
            return template;
        }
    }

    private static final class InMemoryDocumentStorage implements DocumentStorage {

        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private UUID lastRequestId;
        private UUID lastDocumentId;

        @Override
        public String store(UUID requestId, UUID documentId, byte[] content) {
            lastRequestId = requestId;
            lastDocumentId = documentId;
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
