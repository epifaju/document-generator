package com.adgendoc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GeneratedDocumentJpaRepository extends JpaRepository<GeneratedDocumentEntity, UUID> {

    Optional<GeneratedDocumentEntity> findByDocumentIdAndRequestId(UUID documentId, UUID requestId);

    List<GeneratedDocumentEntity> findByRequestIdOrderByCreatedAtAsc(UUID requestId);
}
