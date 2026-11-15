package dev.shivamgulia.filebackup.metadata;

import java.time.Instant;

/** Browse-list entry for a non-deleted file: latest size, last write, and how many versions exist. */
public record FileSummary(String path, long sizeBytes, long versionCount, Instant lastModified) {
}
