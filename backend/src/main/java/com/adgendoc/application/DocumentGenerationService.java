package com.adgendoc.application;

import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.GeneratedDocument;
import com.adgendoc.domain.RequestStatus;
import com.adgendoc.domain.Template;
import com.adgendoc.domain.exceptions.DocumentGenerationException;
import com.adgendoc.domain.exceptions.DocumentNotFoundException;
import com.adgendoc.domain.exceptions.RequestNotFoundException;
import com.adgendoc.domain.exceptions.TemplateNotFoundException;
import com.adgendoc.domain.ports.AuditPort;
import com.adgendoc.domain.ports.DocumentStorage;
import com.adgendoc.domain.ports.GeneratedDocumentRepository;
import com.adgendoc.domain.ports.RequestRepository;
import com.adgendoc.domain.ports.TemplateEngine;
import com.adgendoc.domain.ports.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class DocumentGenerationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentGenerationService.class);

    private static final String ACTOR = "system";
    private static final String DOCX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String DOCX_EXTENSION = ".docx";

    private final RequestRepository requestRepository;
    private final GeneratedDocumentRepository generatedDocumentRepository;
    private final TemplateRepository templateRepository;
    private final TemplateEngine templateEngine;
    private final DocumentStorage documentStorage;
    private final AuditPort auditPort;
    private final Clock clock;

    public DocumentGenerationService(RequestRepository requestRepository,
                                     GeneratedDocumentRepository generatedDocumentRepository,
                                     TemplateRepository templateRepository,
                                     TemplateEngine templateEngine,
                                     DocumentStorage documentStorage,
                                     AuditPort auditPort,
                                     Clock clock) {
        this.requestRepository = Objects.requireNonNull(requestRepository, "requestRepository");
        this.generatedDocumentRepository = Objects.requireNonNull(generatedDocumentRepository,
                "generatedDocumentRepository");
        this.templateRepository = Objects.requireNonNull(templateRepository, "templateRepository");
        this.templateEngine = Objects.requireNonNull(templateEngine, "templateEngine");
        this.documentStorage = Objects.requireNonNull(documentStorage, "documentStorage");
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public GeneratedDocument generate(UUID requestId) {
        DocumentRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
        request.assertCanGenerate();

        try {
            Template template = templateRepository
                    .findActiveByCode(request.getDocumentType())
                    .orElseThrow(() -> new TemplateNotFoundException(request.getDocumentType()));

            byte[] content = templateEngine.merge(template, buildTemplateVariables(request));
            if (content == null || content.length == 0) {
                throw new DocumentGenerationException(
                        ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage());
            }

            UUID documentId = UUID.randomUUID();
            String storagePath = documentStorage.store(requestId, documentId, content);
            GeneratedDocument document = new GeneratedDocument(documentId, requestId, storagePath,
                    DOCX_MIME_TYPE, content.length, sha256Hex(content), Instant.now(clock));
            generatedDocumentRepository.save(document);

            request.transitionTo(RequestStatus.GENERATED, List.of(), Instant.now(clock));
            requestRepository.save(request);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("documentId", documentId.toString());
            details.put("byteSize", document.getByteSize());
            audit(request, "DOCUMENT_GENERATED", details);
            return document;
        } catch (RuntimeException exception) {
            markFailed(request, exception);
            throw asGenerationFailure(exception);
        }
    }

    public DocumentContent getDocument(UUID requestId, UUID documentId) {
        GeneratedDocument document = generatedDocumentRepository
                .findByIdAndRequestId(documentId, requestId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        String storagePath = document.getStoragePath();
        if (storagePath == null
                || !storagePath.toLowerCase(Locale.ROOT).endsWith(DOCX_EXTENSION)) {
            throw new DocumentGenerationException(
                    ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage());
        }
        byte[] bytes = documentStorage.read(storagePath);
        if (bytes == null || bytes.length == 0) {
            throw new DocumentGenerationException(
                    ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage());
        }
        return new DocumentContent(document, bytes);
    }

    /** Documents générés pour une demande (E2, statut {@code GENERATED}). */
    public List<GeneratedDocument> listDocuments(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        return generatedDocumentRepository.findByRequestId(requestId);
    }

    private Map<String, String> buildTemplateVariables(DocumentRequest request) {
        Map<String, Object> data = request.getData();
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("prenom", text(data, "prenom"));
        variables.put("nom", text(data, "nom"));
        variables.put("date_naissance", text(data, "dateNaissance"));
        variables.put("lieu_naissance", text(data, "lieuNaissance"));
        variables.put("nom_incorrect", text(data, "nomIncorrect"));
        variables.put("nom_correct", text(data, "nomCorrect"));
        variables.put("sexe", text(data, "sexe"));
        variables.put("nationalite", text(data, "nationalite"));
        variables.put("document_source_reference", text(data, "documentSourceReference"));
        variables.put("langue_document", text(data, "langueDocument"));
        variables.put("demandeur", text(data, "demandeur"));
        variables.put("motif", text(data, "motif"));
        variables.put("reference_demande", request.getRequestId().toString());
        variables.put("date_generation",
                LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC).toString());
        return variables;
    }

    private String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof String text ? text : "";
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new DocumentGenerationException(ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage(),
                    exception);
        }
    }

    private void markFailed(DocumentRequest request, RuntimeException exception) {
        try {
            if (request.canTransitionTo(RequestStatus.FAILED)) {
                request.transitionTo(RequestStatus.FAILED, List.of(), Instant.now(clock));
                requestRepository.save(request);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("documentType", request.getDocumentType());
            details.put("status", request.getStatus().name());
            details.put("errorCode", errorCodeOf(exception).getCode());
            audit(request, "DOCUMENT_GENERATION_FAILED", details);
        } catch (RuntimeException secondary) {
            LOGGER.warn("Audit ou transition FAILED impossible pour la demande {} : {}",
                    request.getRequestId(), errorCodeOf(secondary).getCode());
        }
    }

    private RuntimeException asGenerationFailure(RuntimeException exception) {
        if (exception instanceof TemplateNotFoundException
                || exception instanceof DocumentGenerationException) {
            return exception;
        }
        return new DocumentGenerationException(ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage(),
                exception);
    }

    private ErrorCode errorCodeOf(RuntimeException exception) {
        if (exception instanceof TemplateNotFoundException typed) {
            return typed.getErrorCode();
        }
        if (exception instanceof DocumentGenerationException typed) {
            return typed.getErrorCode();
        }
        if (exception instanceof RequestNotFoundException typed) {
            return typed.getErrorCode();
        }
        if (exception instanceof DocumentNotFoundException typed) {
            return typed.getErrorCode();
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private void audit(DocumentRequest request, String action, Map<String, Object> details) {
        auditPort.record(request.getRequestId(), action, ACTOR, request.getCorrelationId(),
                details);
    }
}
