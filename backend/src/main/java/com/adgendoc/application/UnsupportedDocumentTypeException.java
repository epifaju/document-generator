package com.adgendoc.application;

import com.adgendoc.domain.ErrorCode;

public class UnsupportedDocumentTypeException extends RuntimeException {

    private final transient ErrorCode errorCode = ErrorCode.ERR_DOCUMENT_TYPE_NON_SUPPORTE;
    private final String documentType;

    public UnsupportedDocumentTypeException(String documentType) {
        super(ErrorCode.ERR_DOCUMENT_TYPE_NON_SUPPORTE.getMessage("documentType"));
        this.documentType = documentType;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getDocumentType() {
        return documentType;
    }
}
