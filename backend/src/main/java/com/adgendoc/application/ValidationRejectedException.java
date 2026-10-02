package com.adgendoc.application;

import com.adgendoc.domain.ErrorCode;

import java.util.List;

public class ValidationRejectedException extends RuntimeException {

    private final transient ErrorCode errorCode = ErrorCode.VALIDATION_ERROR;
    private final List<ValidationOutcome.FieldError> fieldErrors;

    public ValidationRejectedException(List<ValidationOutcome.FieldError> fieldErrors) {
        super(firstMessage(fieldErrors));
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<ValidationOutcome.FieldError> getFieldErrors() {
        return fieldErrors;
    }

    private static String firstMessage(List<ValidationOutcome.FieldError> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return ErrorCode.VALIDATION_ERROR.getMessage();
        }
        return fieldErrors.get(0).message();
    }
}
