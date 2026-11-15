package dev.shivamgulia.filebackup.metadata;

import java.time.Instant;

/** One stored version of a file, as seen through the {@link MetadataStore} port. */
public record VersionRecord(String path, long version, String blobKey, String sha256, long sizeBytes, Instant createdAt) {
}
