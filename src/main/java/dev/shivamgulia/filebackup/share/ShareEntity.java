package dev.shivamgulia.filebackup.share;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a share relationship between users.
 * A share allows a target user to access a specific resource path owned by the owner.
 */
@Entity
@Table(name = "shares")
@Getter
@Setter
@NoArgsConstructor
public class ShareEntity {

    @Id
    @Column(name = "share_id", nullable = false, updatable = false)
    private String shareId = UUID.randomUUID().toString();

    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @Column(name = "target_username", nullable = false)
    private String targetUsername;

    @Column(name = "resource_path", nullable = false)
    private String resourcePath;

    @Column(name = "permission", nullable = false)
    private String permission = "read"; // 'read' or 'read_write'

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;
}
