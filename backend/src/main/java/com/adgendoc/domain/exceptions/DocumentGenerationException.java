package com.adgendoc.domain.exceptions;

import com.adgendoc.domain.ErrorCode;

public class DocumentGenerationException extends RuntimeException {

    private final transient ErrorCode errorCode = ErrorCode.DOCUMENT_GENERATION_ERROR;

    public DocumentGenerationException(String message) {
        super(message);
    }

    public DocumentGenerationException(String message, Throwable cause) {
        super(message, cause);
    }

    public DocumentGenerationException(Throwable cause) {
        super("Échec de génération du document.", cause);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
