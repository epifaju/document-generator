package com.adgendoc.domain.ports;

import com.adgendoc.domain.DocumentRequest;

import java.util.Optional;
import java.util.UUID;

public interface RequestRepository {

    DocumentRequest save(DocumentRequest request);

    Optional<DocumentRequest> findById(UUID requestId);
}
