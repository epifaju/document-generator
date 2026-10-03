package com.adgendoc.infrastructure.persistence;

import com.adgendoc.domain.Template;
import com.adgendoc.domain.ports.TemplateRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Adapter de lecture du port {@code TemplateRepository} : template actif par
 * code (l'index partiel unique {@code uq_template_registry_active_code} de V1
 * garantit au plus un actif par code).
 */
@Repository
public class TemplateRepositoryAdapter implements TemplateRepository {

    private final TemplateRegistryJpaRepository repository;

    public TemplateRepositoryAdapter(TemplateRegistryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Template> findActiveByCode(String code) {
        return repository.findByCodeAndActiveTrue(code).map(TemplateRepositoryAdapter::toDomain);
    }

    private static Template toDomain(TemplateRegistryEntity entity) {
        return new Template(entity.getCode(), entity.getVersion(), entity.getFilePath(),
                entity.getChecksum(), entity.isActive());
    }
}
