package dev.shivamgulia.filebackup.file;

import dev.shivamgulia.filebackup.config.NotFoundException;
import dev.shivamgulia.filebackup.config.UploadConflictException;
import dev.shivamgulia.filebackup.metadata.FileSummary;
import dev.shivamgulia.filebackup.metadata.MetadataStore;
import dev.shivamgulia.filebackup.metadata.VersionRecord;
import dev.shivamgulia.filebackup.storage.FileStorage;
import dev.shivamgulia.filebackup.auth.AuthProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the backup flows. Talks only to the {@link FileStorage} and {@link MetadataStore} ports.
 * Path convention: {@code <username>/photos/<filename>} — the file IS the storage (no blob layer).
 * Bytes exist only for the latest version; version rows keep sha/size history.
 */
@Service
public class BackupService {

    private final FileStorage storage;
    private final MetadataStore metadata;
    private final dev.shivamgulia.filebackup.share.service.ShareService shares;
    private final dev.shivamgulia.filebackup.user.UserRepository users;
    private final int keepVersions;
    private final AuthProperties auth;

    public BackupService(FileStorage storage, MetadataStore metadata,
                         dev.shivamgulia.filebackup.share.service.ShareService shares,
                         dev.shivamgulia.filebackup.user.UserRepository users,
                         @Value("${filebackup.keep-versions:10}") int keepVersions,
                         AuthProperties auth) {
        this.storage = storage;
        this.metadata = metadata;
        this.shares = shares;
        this.users = users;
        this.keepVersions = keepVersions;
        this.auth = auth;
    }

    /** Admin = configured in filebackup.admin-user OR granted the admin role in the DB. */
    private boolean isAdmin(String username) {
        return auth.isAdmin(username)
                || users.findByUsername(username).map(u -> "admin".equals(u.getRole())).orElse(false);
    }

    public VersionRecord upload(String path, String username, InputStream body) {
        requirePath(path);
        requireOwner(path, username);
        CountingInputStream counting = new CountingInputStream(body);
        String sha256 = storage.store(path, counting); // also the sha256 hex of the content
        return commit(path, sha256, counting.count);
    }

    /** Opens {@code path} at {@code version} (latest when null). Caller must stream + checksum-verify. */
    @Transactional
    public Download download(String path, Long version, String username) {
        requirePath(path);
        requireReadAccess(path, username);

        VersionRecord record = (version == null ? metadata.latestVersion(path) : metadata.version(path, version))
                .orElseThrow(() -> new NotFoundException("No version " + (version == null ? "latest" : version) + " for path: " + path));
        if (version != null && metadata.latestVersion(path).map(v -> v.version()).filter(v -> v.equals(version)).isEmpty()) {
            // only the latest bytes exist on disk; older version rows are history only
            throw new NotFoundException("Only the latest version's bytes are stored: " + path);
        }
        return new Download(record, storage.read(path));
    }

    /** Latest-version metadata without opening the file (cheap check for clients, used by HEAD + client script). */
    public VersionRecord latest(String path, String username) {
        requirePath(path);
        requireReadAccess(path, username);
        return metadata.latestVersion(path)
                .orElseThrow(() -> new NotFoundException("No backup for path: " + path));
    }

    public List<FileSummary> list(String prefix, String username) {
        String p = effectivePrefix(prefix, username);
        // p == null only for a non-admin prefixing outside their namespace → empty, never everything
        return p == null ? List.of() : metadata.list(p);
    }

    public void delete(String path, String username) {
        requirePath(path);
        requireOwner(path, username);
        metadata.tombstone(path);
        storage.delete(path); // the file IS the store; history rows stay in the DB
    }

    /** Non-admins are pinned to their own username prefix; anything outside it looks empty (no leaking). */
    private String effectivePrefix(String prefix, String username) {
        if (isAdmin(username)) {
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
        if (!username.equals(ownerOf(path)) && !isAdmin(username)) {
            throw new NotFoundException("No backup for path: " + path);
        }
    }

    /** Read = owner, admin, or an active share for this exact path. Writes stay owner-only. */
    private void requireReadAccess(String path, String username) {
        if (!username.equals(ownerOf(path)) && !isAdmin(username) && !shares.hasShareAccess(path, username)) {
            throw new NotFoundException("No backup for path: " + path);
        }
    }

    // --- presigned uploads (HLD §3.2a) ---

    public record Initiated(String status, String uploadUrl, String method, Long version) {
    }

    /**
     * Same-path dedup → commit immediately; a differently-contented existing file is a conflict;
     * otherwise hand back a signed upload URL. The client's sha256 is trusted for the dedup check —
     * `complete` verifies the size against actual storage.
     */
    public Initiated initiate(String path, String username, String sha256, long sizeBytes) {
        requirePath(path);
        requireOwner(path, username);
        requireSha256(sha256);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Invalid size");
        }
        if (storage.exists(path)) {
            VersionRecord current = metadata.latestVersion(path)
                    .orElseThrow(() -> new UploadConflictException("File exists without a version row: " + path));
            if (!sha256.equals(current.sha256())) {
                throw new UploadConflictException("A different file already exists at " + path);
            }
            if (storage.size(path) != sizeBytes) {
                throw new UploadConflictException("Stored size mismatch for " + path + ": claimed " + sizeBytes);
            }
            VersionRecord saved = commit(path, sha256, sizeBytes);
            return new Initiated("committed", null, null, saved.version());
        }
        return new Initiated("presigned", storage.presignUpload(path), "PUT", null);
    }

    /** Verifies the uploaded file's sha256 + size, then commits the version row. */
    public VersionRecord complete(String path, String username, String sha256, long sizeBytes) {
        requirePath(path);
        requireOwner(path, username);
        requireSha256(sha256);
        if (!storage.exists(path)) {
            throw new UploadConflictException("No uploaded file for " + path);
        }
        long actual = storage.size(path);
        if (actual != sizeBytes) {
            throw new UploadConflictException("Size mismatch for " + path + ": claimed " + sizeBytes + ", stored " + actual);
        }
        if (!sha256.equals(hashOf(path))) {
            throw new UploadConflictException("Checksum mismatch for " + path + ": stored bytes are not sha256 " + sha256);
        }
        return commit(path, sha256, actual);
    }

    /** Streams the stored file once to compute its sha256 — the presigned flow's integrity check. */
    private String hashOf(String path) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(storage.read(path), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not hash " + path, e);
        }
    }

    private VersionRecord commit(String path, String sha256, long sizeBytes) {
        VersionRecord saved = metadata.saveVersion(path, ownerOf(path), sha256, sha256, sizeBytes);
        metadata.versionsToPrune(path, keepVersions).forEach(metadata::deleteVersion);
        return saved;
    }

    static void requireSha256(String sha256) {
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
