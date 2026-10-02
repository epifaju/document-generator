package com.adgendoc.domain.ports;

import com.adgendoc.domain.GeneratedDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GeneratedDocumentRepository {

    GeneratedDocument save(GeneratedDocument document);

    Optional<GeneratedDocument> findByIdAndRequestId(UUID documentId, UUID requestId);

    List<GeneratedDocument> findByRequestId(UUID requestId);
}
