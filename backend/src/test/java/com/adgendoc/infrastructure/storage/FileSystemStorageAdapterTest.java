package com.adgendoc.infrastructure.storage;

import com.adgendoc.domain.exceptions.DocumentGenerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase F2 — {@code FileSystemStorageAdapter} sur répertoire temporaire
 * (jamais le stockage réel du développeur) : écriture/lecture DOCX,
 * document inexistant, isolation entre {@code requestId}, impossibilité de
 * path traversal, création des répertoires, échec d'écriture contrôlé et
 * nettoyage des fichiers temporaires.
 */
class FileSystemStorageAdapterTest {

    @TempDir
    Path root;

    private FileSystemStorageAdapter storage;

    @BeforeEach
    void setUp() {
        storage = new FileSystemStorageAdapter(root.toString());
    }

    @Test
    void store_writes_docx_under_request_and_document_ids_and_creates_directories() {
        UUID requestId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        byte[] content = docx("contenu");

        String storagePath = storage.store(requestId, documentId, content);

        assertThat(storagePath).isEqualTo(requestId + "/" + documentId + ".docx");
        Path written = root.resolve(requestId.toString())
                .resolve(documentId + ".docx");
        assertThat(written).exists();
        assertThat(written.getParent()).isDirectory();
    }

    @Test
    void read_returns_stored_bytes() {
        UUID requestId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        byte[] content = docx("Bonjour Maria");
        String storagePath = storage.store(requestId, documentId, content);

        byte[] read = storage.read(storagePath);

        assertThat(read).isEqualTo(content);
    }

    @Test
    void read_of_missing_document_returns_null() {
        String missing = UUID.randomUUID() + "/" + UUID.randomUUID() + ".docx";

        assertThat(storage.read(missing)).isNull();
    }

    @Test
    void documents_of_different_requests_are_isolated() {
        UUID requestA = UUID.randomUUID();
        UUID requestB = UUID.randomUUID();
        String pathA = storage.store(requestA, UUID.randomUUID(), docx("A"));
        String pathB = storage.store(requestB, UUID.randomUUID(), docx("B"));

        assertThat(pathA).isNotEqualTo(pathB);
        assertThat(storage.read(pathA)).isEqualTo(docx("A"));
        assertThat(storage.read(pathB)).isEqualTo(docx("B"));
        assertThat(root.resolve(requestA.toString())).isDirectory();
        assertThat(root.resolve(requestB.toString())).isDirectory();
    }

    @Test
    void path_traversal_is_rejected_on_read_and_delete() {
        assertThatThrownBy(() -> storage.read("../secret.docx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read("sub/../../secret.docx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("../secret.docx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void read_of_non_docx_extension_is_refused() {
        String hostile = UUID.randomUUID() + "/" + UUID.randomUUID() + ".txt";

        assertThatThrownBy(() -> storage.read(hostile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controlled_write_failure_raises_document_generation_error_and_cleans_temp()
            throws IOException {
        UUID requestId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        // Le chemin cible est déjà un répertoire : le move échoue.
        Path blocked = root.resolve(requestId.toString()).resolve(documentId + ".docx");
        Files.createDirectories(blocked);

        assertThatThrownBy(() -> storage.store(requestId, documentId, docx("x")))
                .isInstanceOf(DocumentGenerationException.class);

        assertThat(temporaryFilesIn(root.resolve(requestId.toString()))).isZero();
    }

    @Test
    void write_failure_when_root_is_a_file_is_controlled() throws IOException {
        Path fileRoot = root.resolve("not-a-directory");
        Files.writeString(fileRoot, "x");
        FileSystemStorageAdapter broken = new FileSystemStorageAdapter(fileRoot.toString());

        assertThatThrownBy(() -> broken.store(UUID.randomUUID(), UUID.randomUUID(),
                docx("x")))
                .isInstanceOf(DocumentGenerationException.class);
    }

    @Test
    void empty_or_null_content_is_refused() {
        UUID requestId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        assertThatThrownBy(() -> storage.store(requestId, documentId, null))
                .isInstanceOf(DocumentGenerationException.class);
        assertThatThrownBy(() -> storage.store(requestId, documentId, new byte[0]))
                .isInstanceOf(DocumentGenerationException.class);
    }

    @Test
    void successful_store_leaves_no_temporary_file() throws IOException {
        UUID requestId = UUID.randomUUID();
        storage.store(requestId, UUID.randomUUID(), docx("x"));

        assertThat(temporaryFilesIn(root.resolve(requestId.toString()))).isZero();
    }

    @Test
    void delete_removes_file_and_is_idempotent() {
        UUID requestId = UUID.randomUUID();
        String storagePath = storage.store(requestId, UUID.randomUUID(), docx("x"));

        storage.delete(storagePath);
        storage.delete(storagePath);

        assertThat(storage.read(storagePath)).isNull();
    }

    private long temporaryFilesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".tmp")).count();
        }
    }

    private byte[] docx(String marker) {
        return ("PK-fake-docx:" + marker).getBytes(StandardCharsets.UTF_8);
    }
}
