package com.adgendoc.api.dto;

import com.adgendoc.domain.RequestStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Réponse d'état d'une demande (contrat API §2.2), utilisée par E1, E2, E3, E4.
 */
public record RequestStatusResponse(
        UUID requestId,
        UUID referenceDemande,
        String documentType,
        RequestStatus status,
        List<String> missingFields,
        List<ErrorResponse.FieldError> fieldErrors,
        Map<String, Object> data,
        List<DocumentRef> documents,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant updatedAt) {

    public RequestStatusResponse {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        data = data == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        documents = documents == null ? List.of() : List.copyOf(documents);
    }

    public record DocumentRef(
            UUID documentId,
            String fileName,
            String contentType,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant generatedAt) {
    }
}
