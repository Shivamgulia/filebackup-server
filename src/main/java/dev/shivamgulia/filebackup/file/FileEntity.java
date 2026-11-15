package dev.shivamgulia.filebackup.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A backed-up logical path (including the owner's username as first path segment).
 * {@code deletedAt} is the tombstone: set means soft-deleted, rows are never removed.
 */
@Entity
@Table(name = "file")
@Getter
@Setter
@NoArgsConstructor
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String path;

    /** Equals the first segment of {@link #path} by the username-prefix access convention. */
    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public FileEntity(String path, String createdBy) {
        this.path = path;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }
}
