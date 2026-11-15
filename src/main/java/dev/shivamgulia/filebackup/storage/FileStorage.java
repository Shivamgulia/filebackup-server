package dev.shivamgulia.filebackup.storage;

import java.io.InputStream;

/**
 * File storage port. Bytes live at the file's own backup path ({@code <owner>/photos/<name>}) —
 * one copy, real names, browsable. {@code store} hashes while writing and returns the sha256 hex.
 */
public interface FileStorage {

    /** Streams {@code in} to {@code path} while hashing; returns the sha256 hex of the content. */
    String store(String path, InputStream in);

    InputStream read(String path);

    void delete(String path);

    boolean exists(String path);

    long size(String path);

    /** Absolute, short-lived (15 min) URL the client PUTs the bytes to, keyed by {@code path}. */
    String presignUpload(String path);
}
