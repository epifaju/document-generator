package com.adgendoc.application;

import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.ErrorCode;
import com.adgendoc.application.ValidationOutcome.FieldError;
import com.adgendoc.domain.ports.AuditPort;
import com.adgendoc.domain.ports.RequestRepository;
import com.adgendoc.domain.exceptions.RequestNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class RequestService {

    private static final String ACTOR = "system";

    private final NormalizationService normalizationService;
    private final ValidationService validationService;
    private final RequestRepository requestRepository;
    private final AuditPort auditPort;
    private final Clock clock;

    public RequestService(NormalizationService normalizationService,
                          ValidationService validationService,
                          RequestRepository requestRepository,
                          AuditPort auditPort,
                          Clock clock) {
        this.normalizationService = Objects.requireNonNull(normalizationService,
                "normalizationService");
        this.validationService = Objects.requireNonNull(validationService, "validationService");
        this.requestRepository = Objects.requireNonNull(requestRepository, "requestRepository");
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DocumentRequest create(String documentType, Map<String, Object> data,
                                  Map<String, Object> extraction, String correlationId) {
        ValidationOutcome envelope = validationService.checkDocumentType(documentType);
        if (!envelope.persistable()) {
            throw notPersistable(documentType, envelope.fieldErrors());
        }
        Map<String, Object> sanitizedExtraction = validationService.sanitizeExtraction(extraction);

        List<FieldError> keyErrors = validationService.checkKeys(data);
        Map<String, Object> normalized = normalizationService.normalize(data);
        ValidationOutcome outcome = keyErrors.isEmpty()
                ? validationService.validate(documentType, normalized)
                : ValidationOutcome.rejected(keyErrors);
        if (!outcome.persistable()) {
            throw notPersistable(documentType, outcome.fieldErrors());
        }

        Instant now = Instant.now(clock);
        Map<String, Object> storableData = validationService.knownFieldsOnly(normalized);
        DocumentRequest request = DocumentRequest.create(documentType,
                buildPayload(documentType, storableData, sanitizedExtraction), correlationId, now);
        request.transitionTo(outcome.status(), outcome.missingFields(), now);
        requestRepository.save(request);
        audit(request, "REQUEST_CREATED");
        return request;
    }

    public DocumentRequest get(UUID requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new RequestNotFoundException(requestId));
    }

    public DocumentRequest patch(UUID requestId, Map<String, Object> partialData) {
        DocumentRequest request = get(requestId);
        request.assertModifiable();

        Map<String, Object> partial = partialData == null ? Map.of() : partialData;
        List<FieldError> keyErrors = validationService.checkKeys(partial);
        if (!keyErrors.isEmpty()) {
            throw new ValidationRejectedException(keyErrors);
        }

        Instant now = Instant.now(clock);
        request.mergeData(partial, now);
        return revalidateAndSave(request, "REQUEST_PATCHED");
    }

    public DocumentRequest validate(UUID requestId) {
        DocumentRequest request = get(requestId);
        request.assertCanValidate();

        return revalidateAndSave(request, "REQUEST_VALIDATED");
    }

    private DocumentRequest revalidateAndSave(DocumentRequest request, String action) {
        Instant now = Instant.now(clock);
        Map<String, Object> rawData = request.getData();
        List<FieldError> keyErrors = validationService.checkKeys(rawData);
        Map<String, Object> normalized = normalizationService.normalize(rawData);
        ValidationOutcome outcome = keyErrors.isEmpty()
                ? validationService.validate(request.getDocumentType(), normalized)
                : ValidationOutcome.rejected(keyErrors);
        if (!outcome.persistable()) {
            throw notPersistable(request.getDocumentType(), outcome.fieldErrors());
        }
        request.replaceData(validationService.knownFieldsOnly(normalized), now);
        request.transitionTo(outcome.status(), outcome.missingFields(), now);
        requestRepository.save(request);
        audit(request, action);
        return request;
    }

    private RuntimeException notPersistable(String documentType, List<FieldError> fieldErrors) {
        boolean unsupportedType = fieldErrors.stream()
                .anyMatch(error -> error.code() == ErrorCode.ERR_DOCUMENT_TYPE_NON_SUPPORTE);
        if (unsupportedType) {
            return new UnsupportedDocumentTypeException(documentType);
        }
        return new ValidationRejectedException(fieldErrors);
    }

    private Map<String, Object> buildPayload(String documentType,
                                             Map<String, Object> normalizedData,
                                             Map<String, Object> extraction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentType", documentType);
        payload.put("data", normalizedData);
        if (extraction != null && !extraction.isEmpty()) {
            payload.put("extraction", extraction);
        }
        return payload;
    }

    private void audit(DocumentRequest request, String action) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentType", request.getDocumentType());
        details.put("status", request.getStatus().name());
        details.put("nbMissingFields", request.getMissingFields().size());
        auditPort.record(request.getRequestId(), action, ACTOR, request.getCorrelationId(),
                details);
    }
}
