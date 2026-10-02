package com.adgendoc.application;

import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.RequestStatus;

import java.util.List;
import java.util.Objects;

public record ValidationOutcome(RequestStatus status,
                                List<String> missingFields,
                                List<FieldError> fieldErrors,
                                boolean persistable) {

    public ValidationOutcome {
        Objects.requireNonNull(status, "status");
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public record FieldError(String field, ErrorCode code, String message) {

        public FieldError {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    public static ValidationOutcome validated() {
        return new ValidationOutcome(RequestStatus.VALIDATED, List.of(), List.of(), true);
    }

    public static ValidationOutcome missing(List<String> missingFields) {
        return new ValidationOutcome(RequestStatus.MISSING_INFORMATION, missingFields,
                List.of(), true);
    }

    public static ValidationOutcome rejected(List<FieldError> fieldErrors) {
        return new ValidationOutcome(RequestStatus.REJECTED, List.of(), fieldErrors, true);
    }

    public static ValidationOutcome rejectedNotPersistable(List<FieldError> fieldErrors) {
        return new ValidationOutcome(RequestStatus.REJECTED, List.of(), fieldErrors, false);
    }
}
