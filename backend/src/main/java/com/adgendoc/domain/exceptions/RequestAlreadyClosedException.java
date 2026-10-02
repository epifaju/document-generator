package com.adgendoc.domain.exceptions;

import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.RequestStatus;

import java.util.UUID;

public class RequestAlreadyClosedException extends RuntimeException {

    private final transient ErrorCode errorCode = ErrorCode.REQUEST_ALREADY_CLOSED;
    private final UUID requestId;
    private final RequestStatus currentStatus;

    public RequestAlreadyClosedException(UUID requestId, RequestStatus currentStatus) {
        super("Demande close : statut « " + currentStatus
                + " », aucune modification possible.");
        this.requestId = requestId;
        this.currentStatus = currentStatus;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public RequestStatus getCurrentStatus() {
        return currentStatus;
    }
}
