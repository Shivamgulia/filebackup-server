package dev.shivamgulia.filebackup.metadata;

import dev.shivamgulia.filebackup.file.FileEntity;
import dev.shivamgulia.filebackup.file.FileRepository;
import dev.shivamgulia.filebackup.file.VersionEntity;
import dev.shivamgulia.filebackup.file.VersionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * JPA/Postgres implementation of the metadata port (local profile).
 */
@Component
@Profile("!aws")
public class JpaMetadataStore implements MetadataStore {

    private final FileRepository files;
    private final VersionRepository versions;

    public JpaMetadataStore(FileRepository files, VersionRepository versions) {
        this.files = files;
        this.versions = versions;
    }

    @Override
    @Transactional
    public VersionRecord saveVersion(String path, String createdBy, String blobKey, String sha256Hex, long sizeBytes) {
        FileEntity file = files.findByPath(path).orElseGet(() -> new FileEntity(path, createdBy));
        file.setDeletedAt(null); // upload to a tombstoned path re-activates it
        file = files.save(file);

        int seq = versions.findTopByFileIdOrderBySeqDesc(file.getId()).map(VersionEntity::getSeq).orElse(0) + 1;
        VersionEntity version = versions.save(new VersionEntity(file, seq, blobKey, HexFormat.of().parseHex(sha256Hex), sizeBytes));
        return toRecord(path, version);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VersionRecord> latestVersion(String path) {
        return files.findByPath(path)
                .filter(f -> f.getDeletedAt() == null) // tombstoned files are invisible to reads
                .flatMap(f -> versions.findTopByFileIdOrderBySeqDesc(f.getId()))
                .map(v -> toRecord(path, v));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VersionRecord> version(String path, long version) {
        return files.findByPath(path)
                .filter(f -> f.getDeletedAt() == null)
                .flatMap(f -> versions.findByFileIdAndSeq(f.getId(), (int) version))
                .map(v -> toRecord(path, v));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FileSummary> list(String pathPrefix) {
        // shortcut: N+1 (one count+latest query per file), fine at personal scale; revisit if browse feels slow
        return files.findByPathStartingWithAndDeletedAtIsNullOrderByPath(pathPrefix).stream()
                .map(f -> new FileSummary(
                        f.getPath(),
                        versions.findTopByFileIdOrderBySeqDesc(f.getId()).map(VersionEntity::getSizeBytes).orElse(0L),
                        versions.countByFileId(f.getId()),
                        versions.findTopByFileIdOrderBySeqDesc(f.getId())
                                .map(VersionEntity::getCreatedAt).orElse(f.getCreatedAt())))
                .toList();
    }

    @Override
    @Transactional
    public void tombstone(String path) {
        FileEntity file = files.findByPath(path)
                .orElseThrow(() -> new dev.shivamgulia.filebackup.config.NotFoundException("No backup for path: " + path));
        file.setDeletedAt(java.time.Instant.now());
        files.save(file);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VersionRecord> versionsToPrune(String path, int keep) {
        return latestVersion(path)
                .map(latest -> versions.findByFileIdAndSeqLessThan(fileIdFor(path), (int) (latest.version() - keep + 1)))
                .orElse(List.of())
                .stream()
                .map(v -> toRecord(path, v))
                .sorted(Comparator.comparingLong(VersionRecord::version))
                .toList();
    }

    @Override
    @Transactional
    public void deleteVersion(VersionRecord record) {
        files.findByPath(record.path())
                .ifPresent(f -> versions.findByFileIdAndSeq(f.getId(), (int) record.version())
                        .ifPresent(versions::delete));
    }

    private Long fileIdFor(String path) {
        return files.findByPath(path).map(FileEntity::getId).orElseThrow();
    }

    private static VersionRecord toRecord(String path, VersionEntity v) {
        return new VersionRecord(path, v.getSeq(), v.getBlobKey(), HexFormat.of().formatHex(v.getSha256()), v.getSizeBytes(), v.getCreatedAt());
    }
}
