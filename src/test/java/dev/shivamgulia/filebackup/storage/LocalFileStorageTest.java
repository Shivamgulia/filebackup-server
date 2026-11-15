package dev.shivamgulia.filebackup.storage;

import dev.shivamgulia.filebackup.storage.BlobUrlSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageTest {

    @TempDir
    Path dir;

    private LocalFileStorage storage() {
        return new LocalFileStorage(dir.resolve("files").toString(), new BlobUrlSigner("test-secret-0123456789abcdef0123456789abcdef"));
    }

    private String sha256Of(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @Test
    void storesTheFileAtItsBackupPathAndReturnsSha256() throws Exception {
        var storage = storage();
        byte[] content = "photo-bytes".getBytes();

        String sha = storage.store("testuser/photos/IMG_2616.HEIC", new ByteArrayInputStream(content));

        assertThat(sha).isEqualTo(sha256Of(content));
        Path file = dir.resolve("files").resolve("testuser/photos/IMG_2616.HEIC");
        assertThat(file).exists();                                  // real name, browsable
        assertThat(new String(Files.readAllBytes(file))).isEqualTo("photo-bytes");
    }

    @Test
    void overwriteReplacesTheBytesInPlace() throws Exception {
        var storage = storage();

        storage.store("testuser/photos/doc.txt", new ByteArrayInputStream("version-1".getBytes()));
        String sha2 = storage.store("testuser/photos/doc.txt", new ByteArrayInputStream("version-2".getBytes()));

        assertThat(sha2).isEqualTo(sha256Of("version-2".getBytes()));
        assertThat(new String(Files.readAllBytes(dir.resolve("files").resolve("testuser/photos/doc.txt"))))
                .isEqualTo("version-2");                            // latest wins, one copy
    }

    @Test
    void readSizeExistsDeleteOperateOnThePath() throws Exception {
        var storage = storage();
        String path = "testuser/photos/x.txt";
        storage.store(path, new ByteArrayInputStream("gone-soon".getBytes()));

        assertThat(storage.exists(path)).isTrue();
        assertThat(storage.size(path)).isEqualTo(9);
        assertThat(new String(storage.read(path).readAllBytes())).isEqualTo("gone-soon");

        storage.delete(path);
        storage.delete("testuser/photos/never-there.txt");          // idempotent

        assertThat(storage.exists(path)).isFalse();
    }

    @Test
    void storeRejectsTraversal() {
        var storage = storage();

        assertThatThrownBy(() -> storage.store("testuser/../escape.txt", new ByteArrayInputStream("x".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dir.resolve("escape.txt")).doesNotExist();
    }

    @Test
    void presignsAnUploadUrlForThePath() {
        var storage = storage();

        String url = storage.presignUpload("testuser/photos/IMG_2616.HEIC");

        assertThat(url).contains("key=testuser%2Fphotos%2FIMG_2616.HEIC").contains("signature=");
    }
}
