package com.adgendoc.infrastructure.persistence;

import com.adgendoc.domain.GeneratedDocument;
import com.adgendoc.domain.ports.GeneratedDocumentRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adapter persistence du port {@code GeneratedDocumentRepository}. */
@Repository
public class GeneratedDocumentRepositoryAdapter implements GeneratedDocumentRepository {

    private final GeneratedDocumentJpaRepository repository;

    public GeneratedDocumentRepositoryAdapter(GeneratedDocumentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public GeneratedDocument save(GeneratedDocument document) {
        GeneratedDocumentEntity saved = repository.save(GeneratedDocumentMapper.toEntity(document));
        return GeneratedDocumentMapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GeneratedDocument> findByIdAndRequestId(UUID documentId, UUID requestId) {
        return repository.findByDocumentIdAndRequestId(documentId, requestId)
                .map(GeneratedDocumentMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GeneratedDocument> findByRequestId(UUID requestId) {
        return repository.findByRequestIdOrderByCreatedAtAsc(requestId).stream()
                .map(GeneratedDocumentMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void delete(UUID documentId) {
        repository.deleteById(documentId);
    }
}
