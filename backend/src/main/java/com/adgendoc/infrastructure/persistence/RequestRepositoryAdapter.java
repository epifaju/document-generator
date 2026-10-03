package com.adgendoc.infrastructure.persistence;

import com.adgendoc.domain.DocumentRequest;
import com.adgendoc.domain.ports.RequestRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter persistence du port {@code RequestRepository} au-dessus de Spring
 * Data JPA (hexagone ADR-06) : aucune règle métier ici, uniquement mapping +
 * appels repository. Le verrouillage optimiste ({@code @Version}) déclenche
 * une {@code DataAccessException} en cas d'écrasement concurrent.
 */
@Repository
public class RequestRepositoryAdapter implements RequestRepository {

    private final DocumentRequestJpaRepository repository;

    public RequestRepositoryAdapter(DocumentRequestJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public DocumentRequest save(DocumentRequest request) {
        DocumentRequestEntity saved = repository.save(DocumentRequestMapper.toEntity(request));
        return DocumentRequestMapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentRequest> findById(UUID requestId) {
        return repository.findById(requestId).map(DocumentRequestMapper::toDomain);
    }
}
