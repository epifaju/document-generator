package com.adgendoc.api;

import com.adgendoc.api.dto.ErrorResponse;
import com.adgendoc.api.dto.GenerateDocumentResponse;
import com.adgendoc.api.dto.RequestStatusResponse;
import com.adgendoc.application.DocumentGenerationService;
import com.adgendoc.application.ValidationOutcome;
import com.adgendoc.application.ValidationService;
import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.GeneratedDocument;
import com.adgendoc.domain.RequestStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mapping domaine → DTO de sortie (architecture §4.1). Recalcule les
 * {@code fieldErrors} d'une demande {@code REJECTED} par re-validation
 * déterministe (les erreurs ne sont pas persistées avec la demande) et
 * charge les documents uniquement pour un statut {@code GENERATED}.
 */
public class RequestResponseMapper {

    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ValidationService validationService;
    private final DocumentGenerationService documentGenerationService;

    public RequestResponseMapper(ValidationService validationService,
                                 DocumentGenerationService documentGenerationService) {
        this.validationService = Objects.requireNonNull(validationService, "validationService");
        this.documentGenerationService = Objects.requireNonNull(documentGenerationService,
                "documentGenerationService");
    }

    public static String fileName(UUID requestId) {
        return "attestation-concordance-" + requestId + ".docx";
    }

    public RequestStatusResponse toStatusResponse(DocumentRequest request) {
        List<ErrorResponse.FieldError> fieldErrors =
                request.getStatus() == RequestStatus.REJECTED
                        ? recomputeFieldErrors(request)
                        : List.of();
        List<RequestStatusResponse.DocumentRef> documents =
                request.getStatus() == RequestStatus.GENERATED
                        ? documents(request.getRequestId())
                        : List.of();
        return new RequestStatusResponse(
                request.getRequestId(),
                request.getRequestId(),
                request.getDocumentType(),
                request.getStatus(),
                request.getMissingFields(),
                fieldErrors,
                request.getData(),
                documents,
                request.getCreatedAt(),
                request.getUpdatedAt());
    }

    public ErrorResponse toMissingResponse(DocumentRequest request, String correlationId) {
        return new ErrorResponse(
                ErrorCode.MISSING_INFORMATION.name(),
                ErrorCode.MISSING_INFORMATION.getMessage(),
                correlationId,
                request.getMissingFields(),
                request.getRequestId(),
                request.getStatus(),
                List.of(),
                null);
    }

    public ErrorResponse toRejectedResponse(DocumentRequest request, String correlationId) {
        return toRejectedResponse(request, correlationId, null);
    }

    /**
     * @param rawData données brutes fournies à la création (E1) : les clés
     * inconnues sont retirées du stockage (aucune donnée hors schéma n'est
     * persistée), elles sont donc re-détectées ici ; {@code null} pour un
     * recalcul sur les données persistées uniquement (E3/E4).
     */
    public ErrorResponse toRejectedResponse(DocumentRequest request, String correlationId,
                                            Map<String, Object> rawData) {
        List<ErrorResponse.FieldError> fieldErrors;
        List<ValidationOutcome.FieldError> rawKeyErrors = rawData == null
                ? List.of()
                : validationService.checkKeys(rawData);
        if (!rawKeyErrors.isEmpty()) {
            // Précédence V2 : une clé invalide a bloqué la validation initiale.
            fieldErrors = toFieldErrors(rawKeyErrors);
        } else {
            fieldErrors = recomputeFieldErrors(request);
        }
        return new ErrorResponse(
                ErrorCode.VALIDATION_ERROR.name(),
                ErrorCode.VALIDATION_ERROR.getMessage(),
                correlationId,
                List.of(),
                request.getRequestId(),
                request.getStatus(),
                fieldErrors,
                null);
    }

    public GenerateDocumentResponse toGenerateResponse(GeneratedDocument document) {
        return new GenerateDocumentResponse(
                document.getRequestId(),
                document.getDocumentId(),
                RequestStatus.GENERATED,
                fileName(document.getRequestId()),
                DOCX_CONTENT_TYPE,
                "/api/v1/requests/" + document.getRequestId()
                        + "/documents/" + document.getDocumentId(),
                document.getCreatedAt());
    }

    public static List<ErrorResponse.FieldError> toFieldErrors(
            List<ValidationOutcome.FieldError> fieldErrors) {
        return fieldErrors.stream()
                .map(error -> ErrorResponse.FieldError.of(error.field(), error.code(),
                        error.message()))
                .toList();
    }

    private List<ErrorResponse.FieldError> recomputeFieldErrors(DocumentRequest request) {
        ValidationOutcome outcome =
                validationService.validate(request.getDocumentType(), request.getData());
        return toFieldErrors(outcome.fieldErrors());
    }

    private List<RequestStatusResponse.DocumentRef> documents(UUID requestId) {
        return documentGenerationService.listDocuments(requestId).stream()
                .map(document -> new RequestStatusResponse.DocumentRef(
                        document.getDocumentId(),
                        fileName(requestId),
                        document.getMimeType(),
                        document.getCreatedAt()))
                .toList();
    }
}
