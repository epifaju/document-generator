package com.adgendoc.infrastructure.persistence;

import com.adgendoc.domain.DocumentRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapping {@code DocumentRequest} (domaine) ⇄ {@code DocumentRequestEntity}
 * (JPA). L'ordre de {@code missingFields} est préservé (liste ordonnée,
 * architecture §5.3) ; le payload est copié pour éviter toute aliasing entre
 * agrégat domaine et entité.
 */
public final class DocumentRequestMapper {

    private DocumentRequestMapper() {
    }

    public static DocumentRequestEntity toEntity(DocumentRequest domain) {
        DocumentRequestEntity entity = new DocumentRequestEntity();
        entity.setRequestId(domain.getRequestId());
        entity.setDocumentType(domain.getDocumentType());
        entity.setStatus(domain.getStatus());
        entity.setPayload(new LinkedHashMap<>(domain.getPayload()));
        entity.setMissingFields(domain.getMissingFields().toArray(new String[0]));
        entity.setCorrelationId(domain.getCorrelationId());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setVersion(domain.getVersion());
        return entity;
    }

    public static DocumentRequest toDomain(DocumentRequestEntity entity) {
        Map<String, Object> payload = entity.getPayload() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(entity.getPayload());
        String[] stored = entity.getMissingFields();
        List<String> missingFields = stored == null
                ? List.of()
                : new ArrayList<>(Arrays.asList(stored));
        return new DocumentRequest(entity.getRequestId(), entity.getDocumentType(),
                entity.getStatus(), payload, missingFields, entity.getCorrelationId(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getVersion() == null ? 0 : entity.getVersion());
    }
}
