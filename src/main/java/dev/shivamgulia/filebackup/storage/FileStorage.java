package dev.shivamgulia.filebackup.storage;

import java.io.InputStream;

/**
 * Blob storage port. Blobs are content-addressed: {@code store} returns the blob key
 * (= sha256 hex of the content), so storing identical content twice is idempotent.
 */
public interface FileStorage {

    /** Streams {@code in} to storage while hashing; returns the blob key (= sha256 hex). */
    String store(InputStream in);

    InputStream read(String blobKey);

    /** Unused in v1 (dedup makes blobs shared across versions; GC is post-v1) — part of the port for completeness. */
    void delete(String blobKey);

    boolean exists(String blobKey);

    long size(String blobKey);

    /** Absolute, short-lived (15 min) URL the client PUTs the bytes to, keyed by {@code blobKey}. */
    String presignUpload(String blobKey);

    /**
     * Exposes the latest version of {@code path} as a real file (by-path view) so humans can
     * browse the backup with actual names. Best-effort cosmetic layer — never fails the backup.
     * Default no-op for stores where a by-path view makes no sense (e.g. S3).
     */
    default void exposeAs(String blobKey, String path) {
    }

    /** Removes the by-path link for {@code path} (soft delete — blobs are kept). Best-effort. */
    default void unexpose(String path) {
    }
}
