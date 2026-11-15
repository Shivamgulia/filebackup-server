package dev.shivamgulia.filebackup.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Filesystem store (local profile). Each file is written directly at its backup path under the
 * files root — temp file + atomic move, hashed while streaming. One copy, real names, browsable.
 */
@Component
@Profile("!aws")
public class LocalFileStorage implements FileStorage {

    private final Path root;
    private final BlobUrlSigner signer;

    public LocalFileStorage(@Value("${filebackup.files-dir}") String root, BlobUrlSigner signer) {
        this.root = Path.of(root);
        this.signer = signer;
    }

    @Override
    public String store(String path, InputStream in) {
        try {
            Path target = resolve(path);
            Files.createDirectories(target.getParent());
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            Path temp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
            try (in) {
                Files.copy(new DigestInputStream(in, sha256), temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("File store failure: " + path, e);
        }
    }

    @Override
    public InputStream read(String path) {
        try {
            return Files.newInputStream(resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException("File not readable: " + path, e);
        }
    }

    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException("File delete failure: " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        return Files.exists(resolve(path));
    }

    @Override
    public long size(String path) {
        try {
            return Files.size(resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException("File not readable: " + path, e);
        }
    }

    @Override
    public String presignUpload(String path) {
        return signer.presign(path);
    }

    /** Resolves a validated backup path under the root; rejects traversal. */
    private Path resolve(String path) {
        if (path.contains("..")) {
            throw new IllegalArgumentException("Path escapes the file store: " + path);
        }
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Path escapes the file store: " + path);
        }
        return target;
    }
}
