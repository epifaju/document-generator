package com.adgendoc.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Entité JPA de la table {@code generated_document} (V1__init.sql). La
 * contrainte FK vers {@code document_request} (ON DELETE RESTRICT) et les
 * CHECK sont posés par Flyway, pas par Hibernate ({@code ddl-auto: validate}).
 */
@Entity
@Table(name = "generated_document")
public class GeneratedDocumentEntity {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "storage_path", length = 1024, nullable = false)
    private String storagePath;

    @Column(name = "mime_type", length = 100, nullable = false)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    /** V1 : {@code CHAR(64)} (pas {@code VARCHAR}) — validation Hibernate. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
