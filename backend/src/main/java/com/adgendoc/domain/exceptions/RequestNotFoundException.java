package com.adgendoc.domain.exceptions;

import com.adgendoc.domain.ErrorCode;

import java.util.UUID;

public class RequestNotFoundException extends RuntimeException {

    private final transient ErrorCode errorCode = ErrorCode.REQUEST_NOT_FOUND;
    private final UUID requestId;

    public RequestNotFoundException(UUID requestId) {
        super("Demande introuvable : " + requestId);
        this.requestId = requestId;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public UUID getRequestId() {
        return requestId;
    }
}
