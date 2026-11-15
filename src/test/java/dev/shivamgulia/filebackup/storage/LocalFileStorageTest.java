package dev.shivamgulia.filebackup.storage;

import dev.shivamgulia.filebackup.storage.BlobUrlSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileStorageTest {

    @TempDir
    Path dir;

    @Test
    void storesContentAddressedAndDedups() throws Exception {
        var storage = new LocalFileStorage(dir.toString(), new BlobUrlSigner("test-secret-0123456789abcdef0123456789abcdef"));
        byte[] content = "same bytes".getBytes();
        String expectedKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));

        String key1 = storage.store(new ByteArrayInputStream(content));
        String key2 = storage.store(new ByteArrayInputStream(content));

        assertThat(key1).isEqualTo(expectedKey);
        assertThat(key2).isEqualTo(key1);              // identical content → identical key
        try (var files = Files.list(dir)) {            // one blob, no leftover temp files
            assertThat(files.filter(p -> !p.getFileName().toString().startsWith("upload-"))).hasSize(1);
        }
        assertThat(new String(storage.read(key1).readAllBytes())).isEqualTo("same bytes");
    }

    @Test
    void exposesBlobAsRealFileUnderByPathView() throws Exception {
        var storage = new LocalFileStorage(dir.resolve("blobs").toString(), new BlobUrlSigner("test-secret-0123456789abcdef0123456789abcdef"));
        String key = storage.store(new ByteArrayInputStream("photo-bytes".getBytes()));

        storage.exposeAs(key, "testuser/photos/2026/11/12/IMG_2616.HEIC");

        Path byPath = dir.resolve("blobs").resolveSibling("files")
                .resolve("testuser/photos/2026/11/12/IMG_2616.HEIC");
        assertThat(byPath).exists();                                   // real name, browsable
        assertThat(new String(Files.readAllBytes(byPath))).isEqualTo("photo-bytes");
        assertThat(Files.getLastModifiedTime(byPath)).isEqualTo(Files.getLastModifiedTime(dir.resolve("blobs").resolve(key)));
    }

    @Test
    void exposesLatestVersionAtSameByPath() throws Exception {
        var storage = new LocalFileStorage(dir.resolve("blobs").toString(), new BlobUrlSigner("test-secret-0123456789abcdef0123456789abcdef"));
        String v1 = storage.store(new ByteArrayInputStream("version-1".getBytes()));
        String v2 = storage.store(new ByteArrayInputStream("version-2".getBytes()));

        storage.exposeAs(v1, "testuser/photos/doc.txt");
        storage.exposeAs(v2, "testuser/photos/doc.txt");

        Path byPath = dir.resolve("blobs").resolveSibling("files").resolve("testuser/photos/doc.txt");
        assertThat(new String(Files.readAllBytes(byPath))).isEqualTo("version-2");   // latest wins
    }

    @Test
    void unexposeRemovesTheByPathLink() throws Exception {
        var storage = new LocalFileStorage(dir.resolve("blobs").toString(), new BlobUrlSigner("test-secret-0123456789abcdef0123456789abcdef"));
        String key = storage.store(new ByteArrayInputStream("gone-soon".getBytes()));
        storage.exposeAs(key, "testuser/photos/x.txt");

        storage.unexpose("testuser/photos/x.txt");
        storage.unexpose("testuser/photos/never-there.txt"); // idempotent

        assertThat(dir.resolve("files").resolve("testuser/photos/x.txt")).doesNotExist();
        assertThat(dir.resolve("blobs").resolve(key)).exists(); // blob kept (soft delete)
    }

    @Test
    void exposesRejectsTraversal() throws Exception {
        var storage = new LocalFileStorage(dir.resolve("blobs").toString(), new BlobUrlSigner("test-secret-0123456789abcdef0123456789abcdef"));
        String key = storage.store(new ByteArrayInputStream("x".getBytes()));

        storage.exposeAs(key, "testuser/../escape.txt");  // best-effort: no exception, nothing written outside

        assertThat(dir.resolve("escape.txt")).doesNotExist();
    }
}
