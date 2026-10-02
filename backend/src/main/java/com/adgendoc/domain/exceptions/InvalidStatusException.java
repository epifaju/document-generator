package com.adgendoc.domain.exceptions;

import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.RequestStatus;

import java.util.UUID;

public class InvalidStatusException extends RuntimeException {

    private final transient ErrorCode errorCode = ErrorCode.INVALID_STATUS;
    private final UUID requestId;
    private final RequestStatus currentStatus;
    private final RequestStatus requiredStatus;

    public InvalidStatusException(UUID requestId, RequestStatus currentStatus,
                                  RequestStatus requiredStatus) {
        super("Statut invalide : statut actuel « " + currentStatus
                + " », statut attendu « " + requiredStatus + " ».");
        this.requestId = requestId;
        this.currentStatus = currentStatus;
        this.requiredStatus = requiredStatus;
    }

    public InvalidStatusException(UUID requestId, RequestStatus currentStatus) {
        super("Statut invalide : action non autorisée pour une demande au statut « "
                + currentStatus + " ».");
        this.requestId = requestId;
        this.currentStatus = currentStatus;
        this.requiredStatus = null;
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

    public RequestStatus getRequiredStatus() {
        return requiredStatus;
    }
}
