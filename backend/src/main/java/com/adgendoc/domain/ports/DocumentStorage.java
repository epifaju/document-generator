package com.adgendoc.domain.ports;

import java.util.UUID;

public interface DocumentStorage {

    String store(UUID requestId, UUID documentId, byte[] content);

    byte[] read(String storagePath);

    /** Suppression compensatoire d'un document stocké (échec post-écriture). */
    void delete(String storagePath);
}
