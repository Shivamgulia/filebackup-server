package dev.shivamgulia.filebackup.file;

import dev.shivamgulia.filebackup.config.NotFoundException;
import dev.shivamgulia.filebackup.config.UploadConflictException;
import dev.shivamgulia.filebackup.metadata.FileSummary;
import dev.shivamgulia.filebackup.metadata.MetadataStore;
import dev.shivamgulia.filebackup.metadata.VersionRecord;
import dev.shivamgulia.filebackup.storage.FileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Orchestrates the backup flows. Talks only to the {@link FileStorage} and {@link MetadataStore} ports.
 * Path convention: {@code <username>/rest/of/path} — {@code createdBy} is the first segment.
 * The auth step will enforce that the JWT user matches it; for now it is derived, not checked.
 */
@Service
public class BackupService {

    private final FileStorage blobs;
    private final MetadataStore metadata;
    private final int keepVersions;
    private final String adminUser;

    public BackupService(FileStorage blobs, MetadataStore metadata,
                         @Value("${filebackup.keep-versions:10}") int keepVersions,
                         @Value("${filebackup.admin-user}") String adminUser) {
        this.blobs = blobs;
        this.metadata = metadata;
        this.keepVersions = keepVersions;
        this.adminUser = adminUser;
    }

    public VersionRecord upload(String path, String username, InputStream body) {
        requirePath(path);
        requireOwner(path, username);
        CountingInputStream counting = new CountingInputStream(body);
        String blobKey = blobs.store(counting); // also the sha256 hex of the content
        VersionRecord saved = metadata.saveVersion(path, ownerOf(path), blobKey, blobKey, counting.count);
        blobs.exposeAs(blobKey, path);
        metadata.versionsToPrune(path, keepVersions).forEach(metadata::deleteVersion);
        return saved;
    }

    /** Opens the blob for {@code path} at {@code version} (latest when null). Caller must stream + checksum-verify. */
    public Download download(String path, Long version, String username) {
        requirePath(path);
        requireOwner(path, username);
        VersionRecord record = (version == null ? metadata.latestVersion(path) : metadata.version(path, version))
                .orElseThrow(() -> new NotFoundException("No version " + (version == null ? "latest" : version) + " for path: " + path));
        return new Download(record, blobs.read(record.blobKey()));
    }

    /** Latest-version metadata without opening the blob (cheap check for clients, used by HEAD + client script). */
    public VersionRecord latest(String path, String username) {
        requirePath(path);
        requireOwner(path, username);
        return metadata.latestVersion(path)
                .orElseThrow(() -> new NotFoundException("No backup for path: " + path));
    }

    public List<FileSummary> list(String prefix, String username) {
        String p = effectivePrefix(prefix, username);
        return metadata.list(p);
    }

    public void delete(String path, String username) {
        requirePath(path);
        requireOwner(path, username);
        metadata.tombstone(path);
        blobs.unexpose(path); // remove the browsable by-path link; blobs and history stay
    }

    /** Non-admins are pinned to their own username prefix; anything outside it looks empty (no leaking). */
    private String effectivePrefix(String prefix, String username) {
        if (username.equals(adminUser)) {
            return (prefix == null || prefix.isBlank()) ? "" : prefix;
        }
        String own = username + "/";
        if (prefix == null || prefix.isBlank()) {
            return own;
        }
        return prefix.startsWith(own) ? prefix : null;
    }

    /** Cross-user access is a 404, not a 403 — don't confirm other users' paths exist. Admin bypasses. */
    private void requireOwner(String path, String username) {
        if (!username.equals(ownerOf(path)) && !username.equals(adminUser)) {
            throw new NotFoundException("No backup for path: " + path);
        }
    }

    // --- presigned uploads (HLD §3.2a) ---

    public record Initiated(String status, String uploadUrl, String method, Long version) {
    }

    /**
     * Dedup → commit immediately; otherwise hand back a signed upload URL.
     * The client's sha256 is only trusted for presigning — `complete` verifies against actual storage.
     */
    public Initiated initiate(String path, String username, String sha256, long sizeBytes) {
        requirePath(path);
        requireOwner(path, username);
        requireSha256(sha256);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Invalid size");
        }
        if (blobs.exists(sha256)) {
            VersionRecord saved = metadata.saveVersion(path, ownerOf(path), sha256, sha256, sizeBytes);
            blobs.exposeAs(sha256, path);
            metadata.versionsToPrune(path, keepVersions).forEach(metadata::deleteVersion);
            return new Initiated("committed", null, null, saved.version());
        }
        return new Initiated("presigned", blobs.presignUpload(sha256), "PUT", null);
    }

    /** Verifies the blob really landed under the claimed hash, then commits the version row. */
    public VersionRecord complete(String path, String username, String sha256, long sizeBytes) {
        requirePath(path);
        requireOwner(path, username);
        requireSha256(sha256);
        if (!blobs.exists(sha256)) {
            throw new UploadConflictException("No uploaded blob under key " + sha256);
        }
        long actual = blobs.size(sha256);
        if (actual != sizeBytes) {
            throw new UploadConflictException("Size mismatch for blob " + sha256 + ": claimed " + sizeBytes + ", stored " + actual);
        }
        VersionRecord saved = metadata.saveVersion(path, ownerOf(path), sha256, sha256, actual);
        blobs.exposeAs(sha256, path);
        metadata.versionsToPrune(path, keepVersions).forEach(metadata::deleteVersion);
        return saved;
    }

    private static void requireSha256(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a 64-char lowercase hex string");
        }
    }

    static void requirePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.endsWith("/")
                || path.contains("//") || path.contains("..") || !path.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("Invalid backup path: " + path);
        }
        if (path.indexOf('/') <= 0) {
            throw new IllegalArgumentException("Path must start with the owner username: " + path);
        }
    }

    static String ownerOf(String path) {
        return path.substring(0, path.indexOf('/'));
    }

    record Download(VersionRecord record, InputStream content) {
    }

    /** Counts bytes read so the service can record the version size without buffering the stream. */
    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }
}
