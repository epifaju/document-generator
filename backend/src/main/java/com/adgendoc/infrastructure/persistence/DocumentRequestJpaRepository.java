package com.adgendoc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentRequestJpaRepository extends JpaRepository<DocumentRequestEntity, UUID> {
}
