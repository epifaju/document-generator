package com.adgendoc.api.dto;

import java.util.List;

/**
 * Réponse de {@code POST /api/v1/extraction/validate} (contrat API §2.6).
 */
public record ExtractionValidationResponse(
        boolean valid,
        String correlationId,
        List<ErrorResponse.SchemaError> errors) {

    public ExtractionValidationResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
