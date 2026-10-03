package com.adgendoc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TemplateRegistryJpaRepository
        extends JpaRepository<TemplateRegistryEntity, TemplateRegistryEntity.TemplateKey> {

    Optional<TemplateRegistryEntity> findByCodeAndActiveTrue(String code);
}
