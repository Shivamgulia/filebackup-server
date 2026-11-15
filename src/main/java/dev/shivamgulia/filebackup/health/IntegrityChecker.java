package dev.shivamgulia.filebackup.health;

import dev.shivamgulia.filebackup.metadata.MetadataStore;
import dev.shivamgulia.filebackup.metadata.VersionRecord;
import dev.shivamgulia.filebackup.storage.FileStorage;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Verifies the latest version's blob of every non-deleted file against its stored sha256.
 * Personal scale = fine to do synchronously on /api/health.
 */
@Component
public class IntegrityChecker {

    private final MetadataStore metadata;
    private final FileStorage blobs;

    public IntegrityChecker(MetadataStore metadata, FileStorage blobs) {
        this.metadata = metadata;
        this.blobs = blobs;
    }

    record CorruptFile(String path, long version) {
    }

    record HealthReport(String status, int filesChecked, java.util.List<CorruptFile> corrupt) {
    }

    HealthReport check() {
        var summaries = metadata.list("");
        java.util.List<CorruptFile> corrupt = summaries.stream()
                .flatMap(s -> metadata.latestVersion(s.path()).stream())
                .filter(r -> !matches(r))
                .map(r -> new CorruptFile(r.path(), r.version()))
                .toList();
        return new HealthReport(corrupt.isEmpty() ? "UP" : "DEGRADED", summaries.size(), corrupt);
    }

    private boolean matches(VersionRecord record) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(blobs.read(record.blobKey()), sha256)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (Exception e) {
            return false; // unreadable = corrupt for reporting purposes
        }
        return HexFormat.of().formatHex(sha256.digest()).equals(record.sha256());
    }
}
