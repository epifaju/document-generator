package com.adgendoc.infrastructure.storage;

import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.exceptions.DocumentGenerationException;
import com.adgendoc.domain.ports.DocumentStorage;

import java.util.UUID;

/**
 * Placeholder F1 (phase F1 : pas de stockage fichier réel). Le stockeur réel
 * {@code FileSystemStorageAdapter} sera implanté dans une phase ultérieure —
 * il est explicitement hors périmètre F1.
 *
 * <p>Aucun fichier n'est écrit ni lu : toute opération est rejetée avec un
 * code classifié ({@code DOCUMENT_GENERATION_ERROR}).</p>
 */
public class UnavailableDocumentStorage implements DocumentStorage {

    @Override
    public String store(UUID requestId, UUID documentId, byte[] content) {
        throw new DocumentGenerationException(ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage());
    }

    @Override
    public byte[] read(String storagePath) {
        throw new DocumentGenerationException(ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage());
    }
}
