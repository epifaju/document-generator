package com.adgendoc.api.dto;

import com.adgendoc.domain.RequestStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

/**
 * Réponse de {@code POST /api/v1/requests/{requestId}/generate}
 * (contrat API §2.5).
 */
public record GenerateDocumentResponse(
        UUID requestId,
        UUID documentId,
        RequestStatus status,
        String fileName,
        String contentType,
        String downloadPath,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant generatedAt) {
}
