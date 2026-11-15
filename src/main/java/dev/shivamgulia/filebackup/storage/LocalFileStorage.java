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
 * Filesystem blob store (local profile). Content-addressed: blob name = sha256 hex,
 * written via temp file + atomic move, so identical content is stored exactly once.
 */
@Component
@Profile("!aws")
public class LocalFileStorage implements FileStorage {

    private final Path root;
    private final Path filesRoot;
    private final BlobUrlSigner signer;

    public LocalFileStorage(@Value("${filebackup.blobs-dir}") String root, BlobUrlSigner signer) {
        this.root = Path.of(root);
        this.filesRoot = this.root.getParent() != null ? this.root.getParent().resolve("files") : Path.of("files");
        this.signer = signer;
    }

    @Override
    public String store(InputStream in) {
        try {
            Files.createDirectories(root);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            Path temp = Files.createTempFile(root, "upload-", ".tmp");
            try (in) {
                Files.copy(new DigestInputStream(in, sha256), temp, StandardCopyOption.REPLACE_EXISTING);
            }
            String key = HexFormat.of().formatHex(sha256.digest());
            Path blob = root.resolve(key);
            if (!Files.exists(blob)) {
                Files.move(temp, blob, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.delete(temp); // dedup: identical content already stored
            }
            return key;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Blob store failure", e);
        }
    }

    @Override
    public InputStream read(String blobKey) {
        try {
            return Files.newInputStream(root.resolve(blobKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Blob not readable: " + blobKey, e);
        }
    }

    @Override
    public void delete(String blobKey) {
        try {
            Files.deleteIfExists(root.resolve(blobKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Blob delete failure: " + blobKey, e);
        }
    }

    @Override
    public boolean exists(String blobKey) {
        return Files.exists(root.resolve(blobKey));
    }

    /** Hard link under {@code <blobs-dir>/../files/<path>} so the backup is browsable with real
     * file names. Hard links cost no extra space and keep pointing at the content even if an old
     * blob version is pruned. Best-effort: failures are logged, never propagated. */
    @Override
    public void exposeAs(String blobKey, String path) {
        try {
            Path target = filesRoot.resolve(path).normalize();
            if (!target.startsWith(filesRoot.normalize())) {
                throw new IllegalArgumentException("Path escapes the by-path view: " + path);
            }
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                Files.delete(target);
            }
            Files.createLink(target, root.resolve(blobKey).normalize());
        } catch (IOException | IllegalArgumentException e) {
            System.getLogger("LocalFileStorage").log(System.Logger.Level.WARNING,
                    "By-path view unavailable for " + path + ": " + e);
        }
    }

    @Override
    public void unexpose(String path) {
        try {
            Files.deleteIfExists(filesRoot.resolve(path).normalize());
        } catch (IOException e) {
            System.getLogger("LocalFileStorage").log(System.Logger.Level.WARNING,
                    "Could not remove by-path entry for " + path + ": " + e);
        }
    }

    @Override
    public long size(String blobKey) {
        try {
            return Files.size(root.resolve(blobKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Blob not readable: " + blobKey, e);
        }
    }

    @Override
    public String presignUpload(String blobKey) {
        return signer.presign(blobKey);
    }
}
