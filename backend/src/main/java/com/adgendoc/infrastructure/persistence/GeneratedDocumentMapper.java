package com.adgendoc.infrastructure.persistence;

import com.adgendoc.domain.GeneratedDocument;

/**
 * Mapping {@code GeneratedDocument} (domaine) ⇄ {@code GeneratedDocumentEntity}
 * (JPA) — agrégat domaine immuable.
 */
public final class GeneratedDocumentMapper {

    private GeneratedDocumentMapper() {
    }

    public static GeneratedDocumentEntity toEntity(GeneratedDocument domain) {
        GeneratedDocumentEntity entity = new GeneratedDocumentEntity();
        entity.setDocumentId(domain.getDocumentId());
        entity.setRequestId(domain.getRequestId());
        entity.setStoragePath(domain.getStoragePath());
        entity.setMimeType(domain.getMimeType());
        entity.setByteSize(domain.getByteSize());
        entity.setSha256(domain.getSha256());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }

    public static GeneratedDocument toDomain(GeneratedDocumentEntity entity) {
        return new GeneratedDocument(entity.getDocumentId(), entity.getRequestId(),
                entity.getStoragePath(), entity.getMimeType(), entity.getByteSize(),
                entity.getSha256(), entity.getCreatedAt());
    }
}
