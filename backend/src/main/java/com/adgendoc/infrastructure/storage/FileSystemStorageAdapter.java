package com.adgendoc.infrastructure.storage;

import com.adgendoc.domain.ErrorCode;
import com.adgendoc.domain.exceptions.DocumentGenerationException;
import com.adgendoc.domain.ports.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

/**
 * Stockage filesystem déterministe (architecture §6.3) :
 *
 * <ul>
 *   <li>racine <b>uniquement</b> {@code app.document.storage-path} — aucun
 *       chemin fourni par l'utilisateur n'est jamais accepté ;</li>
 *   <li>structure {@code <requestId>/<documentId>.docx} avec identifiants
 *       générés côté serveur (UUID) ; toute écriture/lecture hors racine
 *       (path traversal) est rejetée ;</li>
 *   <li>écriture via fichier temporaire dans le répertoire cible puis
 *       {@code move} atomique quand possible — pas de fichier final
 *       partiellement écrit ; temporaire supprimé en cas d'échec ;</li>
 *   <li>lecture limitée à l'extension {@code .docx} ; fichier inexistant ⇒
 *       {@code null} ;</li>
 *   <li>chemin retourné = référence interne relative
 *       {@code <requestId>/<documentId>.docx} (jamais contrôlable par
 *       l'utilisateur).</li>
 * </ul>
 *
 * <p>Logs : uniquement {@code requestId}/{@code documentId}/taille — ni
 * contenu, ni valeurs (AGENTS.md §13).</p>
 */
public class FileSystemStorageAdapter implements DocumentStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemStorageAdapter.class);
    private static final String DOCX_EXTENSION = ".docx";

    private final Path root;

    public FileSystemStorageAdapter(String storagePath) {
        Objects.requireNonNull(storagePath, "storagePath");
        this.root = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @Override
    public String store(UUID requestId, UUID documentId, byte[] content) {
        if (content == null || content.length == 0) {
            throw new DocumentGenerationException(
                    ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage());
        }
        Path directory = root.resolve(requestId.toString()).normalize();
        if (!directory.startsWith(root)) {
            throw new DocumentGenerationException(
                    ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage());
        }
        Path target = directory.resolve(documentId + DOCX_EXTENSION).normalize();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, documentId + "-", ".tmp");
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String storagePath = requestId + "/" + documentId + DOCX_EXTENSION;
            LOGGER.debug("Document stocke : requestId={} documentId={} byteSize={}",
                    requestId, documentId, content.length);
            return storagePath;
        } catch (IOException exception) {
            throw new DocumentGenerationException(
                    ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage(), exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanup) {
                    LOGGER.warn("Nettoyage fichier temporaire impossible : requestId={}",
                            requestId);
                }
            }
        }
    }

    @Override
    public byte[] read(String storagePath) {
        Path target = resolveInsideRoot(storagePath);
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                .endsWith(DOCX_EXTENSION)) {
            throw new IllegalArgumentException(
                    "Lecture refusee : extension .docx exigee.");
        }
        try {
            if (!Files.isRegularFile(target)) {
                return null;
            }
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new DocumentGenerationException(
                    ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage(), exception);
        }
    }

    @Override
    public void delete(String storagePath) {
        Path target = resolveInsideRoot(storagePath);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new DocumentGenerationException(
                    ErrorCode.DOCUMENT_GENERATION_ERROR.getMessage(), exception);
        }
    }

    /** Canonicalisation + garde anti path traversal (lecture et suppression). */
    private Path resolveInsideRoot(String storagePath) {
        if (storagePath == null || storagePath.isBlank() || storagePath.contains("..")) {
            throw new IllegalArgumentException("Chemin de stockage invalide.");
        }
        Path target = root.resolve(storagePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Chemin de stockage hors racine.");
        }
        return target;
    }
}
