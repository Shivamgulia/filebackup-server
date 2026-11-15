package dev.shivamgulia.filebackup.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * One version of a {@link FileEntity}. {@code seq} is the per-file version number (1,2,3…).
 * {@code blobKey} is the storage address of the blob (fs path name / S3 key, = sha256 hex);
 * {@code sha256} is the raw hash used for integrity verification on download.
 */
@Entity
@Table(name = "version",
        uniqueConstraints = @UniqueConstraint(columnNames = {"file_id", "seq"}))
@Getter
@Setter
@NoArgsConstructor
public class VersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private FileEntity file;

    @Column(nullable = false)
    private int seq;

    @Column(name = "blob_key", nullable = false, columnDefinition = "text")
    private String blobKey;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] sha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public VersionEntity(FileEntity file, int seq, String blobKey, byte[] sha256, long sizeBytes) {
        this.file = file;
        this.seq = seq;
        this.blobKey = blobKey;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.createdAt = Instant.now();
    }
}
