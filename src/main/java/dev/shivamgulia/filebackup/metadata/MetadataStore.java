package dev.shivamgulia.filebackup.metadata;

import java.util.List;
import java.util.Optional;

/**
 * Backup metadata port: files, their versioned history, tombstones.
 * Implementations: JPA/Postgres (local), DynamoDB (aws).
 */
public interface MetadataStore {

    /**
     * Records a new version of {@code path} (creating the file row on first upload, clearing any
     * tombstone) and returns it. The seq number (= version number) is per-file, 1,2,3….
     */
    VersionRecord saveVersion(String path, String createdBy, String blobKey, String sha256Hex, long sizeBytes);

    Optional<VersionRecord> latestVersion(String path);

    /** {@code version} is the per-file seq number. */
    Optional<VersionRecord> version(String path, long version);

    /** All non-deleted files whose path starts with {@code pathPrefix}, ordered by path. */
    List<FileSummary> list(String pathPrefix);

    /** Soft delete: stamps deleted_at. Blobs and history are kept. */
    void tombstone(String path);

    /** Versions of {@code path} beyond the newest {@code keep} (oldest first) — candidates for pruning. */
    List<VersionRecord> versionsToPrune(String path, int keep);

    void deleteVersion(VersionRecord record);
}
