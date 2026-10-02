package com.adgendoc.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class GeneratedDocument {

    private final UUID documentId;
    private final UUID requestId;
    private final String storagePath;
    private final String mimeType;
    private final long byteSize;
    private final String sha256;
    private final Instant createdAt;

    public GeneratedDocument(UUID documentId, UUID requestId, String storagePath,
                             String mimeType, long byteSize, String sha256,
                             Instant createdAt) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.storagePath = Objects.requireNonNull(storagePath, "storagePath");
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        this.byteSize = byteSize;
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public String getSha256() {
        return sha256;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
