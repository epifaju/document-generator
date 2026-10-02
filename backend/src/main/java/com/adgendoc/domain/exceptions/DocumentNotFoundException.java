package com.adgendoc.domain.exceptions;

import com.adgendoc.domain.ErrorCode;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    private final transient ErrorCode errorCode = ErrorCode.DOCUMENT_NOT_FOUND;
    private final UUID documentId;

    public DocumentNotFoundException(UUID documentId) {
        super("Document introuvable : " + documentId);
        this.documentId = documentId;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}
