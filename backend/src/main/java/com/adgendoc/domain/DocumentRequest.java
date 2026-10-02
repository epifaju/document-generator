package com.adgendoc.domain;

import com.adgendoc.domain.exceptions.InvalidStatusException;
import com.adgendoc.domain.exceptions.RequestAlreadyClosedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class DocumentRequest {

    private UUID requestId;
    private String documentType;
    private RequestStatus status;
    private Map<String, Object> payload;
    private List<String> missingFields;
    private String correlationId;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;

    public DocumentRequest(UUID requestId, String documentType, RequestStatus status,
                           Map<String, Object> payload, List<String> missingFields,
                           String correlationId, Instant createdAt, Instant updatedAt,
                           int version) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.documentType = Objects.requireNonNull(documentType, "documentType");
        this.status = Objects.requireNonNull(status, "status");
        this.payload = copyMap(payload);
        this.missingFields = copyList(missingFields);
        this.correlationId = correlationId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static DocumentRequest create(String documentType, Map<String, Object> payload,
                                         String correlationId, Instant now) {
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(now, "now");
        return new DocumentRequest(UUID.randomUUID(), documentType, RequestStatus.DRAFT,
                payload, List.of(), correlationId, now, now, 0);
    }

    public boolean canTransitionTo(RequestStatus target) {
        return status.canTransitionTo(target);
    }

    public void transitionTo(RequestStatus target, List<String> missingFields, Instant timestamp) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timestamp, "timestamp");
        if (status.isTerminal()) {
            throw new RequestAlreadyClosedException(requestId, status);
        }
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusException(requestId, status, target);
        }
        if (target == RequestStatus.MISSING_INFORMATION
                && (missingFields == null || missingFields.isEmpty())) {
            throw new IllegalArgumentException(
                    "missingFields must not be empty for MISSING_INFORMATION");
        }
        this.status = target;
        this.missingFields = target == RequestStatus.MISSING_INFORMATION
                ? copyList(missingFields)
                : List.of();
        this.updatedAt = timestamp;
    }

    public void mergeData(Map<String, Object> providedFields, Instant timestamp) {
        Objects.requireNonNull(providedFields, "providedFields");
        Objects.requireNonNull(timestamp, "timestamp");
        assertModifiable();
        Map<String, Object> envelope = new LinkedHashMap<>(payload);
        Map<String, Object> data = new LinkedHashMap<>();
        Object currentData = envelope.get("data");
        if (currentData instanceof Map<?, ?> map) {
            map.forEach((key, value) -> data.put(String.valueOf(key), value));
        }
        data.putAll(providedFields);
        envelope.put("data", data);
        this.payload = envelope;
        this.updatedAt = timestamp;
    }

    public void replaceData(Map<String, Object> normalizedData, Instant timestamp) {
        Objects.requireNonNull(normalizedData, "normalizedData");
        Objects.requireNonNull(timestamp, "timestamp");
        assertModifiable();
        Map<String, Object> envelope = new LinkedHashMap<>(payload);
        envelope.put("data", new LinkedHashMap<>(normalizedData));
        this.payload = envelope;
        this.updatedAt = timestamp;
    }

    public void assertModifiable() {
        if (status.isTerminal()) {
            throw new RequestAlreadyClosedException(requestId, status);
        }
    }

    public void assertCanValidate() {
        if (status.isTerminal()) {
            throw new InvalidStatusException(requestId, status);
        }
    }

    public void assertCanGenerate() {
        if (!status.canGenerate()) {
            throw new InvalidStatusException(requestId, status, RequestStatus.VALIDATED);
        }
    }

    public Map<String, Object> getData() {
        Object data = payload.get("data");
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        return new LinkedHashMap<>();
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = copyMap(payload);
    }

    public List<String> getMissingFields() {
        return missingFields;
    }

    public void setMissingFields(List<String> missingFields) {
        this.missingFields = copyList(missingFields);
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static List<String> copyList(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }
}
