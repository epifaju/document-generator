package com.adgendoc.api.dto;

import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.RequestStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Corps des réponses d'erreur (contrat API §2.3).
 *
 * <p>{@code correlationId}, {@code missingFields} et {@code fieldErrors} sont
 * toujours présents ; {@code requestId}, {@code status} et {@code errors}
 * sont omis lorsqu'ils ne s'appliquent pas.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        String correlationId,
        List<String> missingFields,
        UUID requestId,
        RequestStatus status,
        List<FieldError> fieldErrors,
        List<SchemaError> errors) {

    public ErrorResponse {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public record FieldError(String field, String code, String message) {

        public static FieldError of(String field, ErrorCode code, String message) {
            return new FieldError(field, code.name(), message);
        }
    }

    public record SchemaError(String path, ErrorCode code, String message) {

        public SchemaError {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(message, "message");
            code = code == null ? ErrorCode.ERR_PAYLOAD_INVALIDE : code;
        }

        public static SchemaError of(String path, ErrorCode code, String message) {
            return new SchemaError(path, code, message);
        }
    }
}
