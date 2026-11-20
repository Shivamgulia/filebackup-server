package dev.shivamgulia.filebackup.share;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ShareEntity operations.
 */
public interface ShareRepository extends JpaRepository<ShareEntity, String> {

    /**
     * Find active (non-revoked, non-expired) shares owned by a specific user.
     * @param ownerUsername the username of the share owner
     * @param targetUsername the username of the target user (optional, filter by specific recipient)
     * @param resourcePath the resource path (optional, filter by specific path)
     * @param permission the permission type (optional, filter by read/read_write)
     * @return list of active shares
     */
    default List<ShareEntity> findActiveSharesByOwner(String ownerUsername, String targetUsername, String resourcePath, String permission) {
        // Base query: non-revoked, not expired
        // In a real implementation, we'd use @Query with native query
        // For now, this is a placeholder - we'll implement custom finder methods
        return null;
    }

    /**
     * Find an active share by owner and target username and resource path.
     * @param ownerUsername the username of the share owner
     * @param targetUsername the username of the target user
     * @param resourcePath the resource path being shared
     * @return the active share, or null if not found
     */
    Optional<ShareEntity> findByOwnerUsernameAndTargetUsernameAndResourcePathAndRevokedFalseAndExpiresAtIsNull(String ownerUsername, String targetUsername, String resourcePath);

    /**
     * Find active shares by owner username (all targets, all paths).
     * @param ownerUsername the username of the share owner
     * @return list of active shares
     */
    List<ShareEntity> findByOwnerUsernameAndRevokedFalseAndExpiresAtIsNull(String ownerUsername);

    /**
     * Find active shares targeting a specific user (from any owner).
     * @param targetUsername the username of the target user
     * @return list of active shares targeting this user
     */
    List<ShareEntity> findByTargetUsernameAndRevokedFalseAndExpiresAtIsNull(String targetUsername);
}
