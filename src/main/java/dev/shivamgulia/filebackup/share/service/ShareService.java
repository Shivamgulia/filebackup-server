package dev.shivamgulia.filebackup.share.service;

import dev.shivamgulia.filebackup.share.ShareEntity;
import dev.shivamgulia.filebackup.share.ShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service layer for share operations.
 * Handles creating, listing, and revoking shares with proper validation.
 */
@Service
@RequiredArgsConstructor
public class ShareService {

    private final ShareRepository shareRepository;

    /**
     * Create a new share for a resource path.
     * Only the owner of the resource can create a share for it.
     *
     * @param ownerUsername the username of the share owner
     * @param targetUsername the username of the recipient
     * @param resourcePath the file/folder path to share
     * @param permission 'read' or 'read_write'
     * @param expiresAt optional expiration timestamp, or null for no expiration
     * @return the created share entity
     * @throws IllegalArgumentException if the owner doesn't own the resource or invalid params
     */
    @Transactional
    public ShareEntity createShare(String ownerUsername, String targetUsername, String resourcePath, String permission, Instant expiresAt) {
        // Validate permission
        if (!permission.equals("read") && !permission.equals("read_write")) {
            throw new IllegalArgumentException("Invalid permission: " + permission + ". Must be 'read' or 'read_write'");
        }

        // Validate target username
        if (targetUsername == null || targetUsername.trim().isEmpty()) {
            throw new IllegalArgumentException("Target username is required");
        }

        // Validate resource path
        if (resourcePath == null || resourcePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Resource path is required");
        }

        // Check that the owner actually owns this path
        String ownerOfPath = resourcePath.split("/")[0];
        if (!ownerOfPath.equals(ownerUsername)) {
            throw new IllegalArgumentException("You can only share files you own. Path owner: " + ownerOfPath + ", you: " + ownerUsername);
        }

        // Check if a share already exists for this owner/target/path combination
        var existing = shareRepository.findFirstByOwnerUsernameAndTargetUsernameAndResourcePathAndRevokedFalse(
                ownerUsername, targetUsername, resourcePath);
        if (existing.isPresent()) {
            // Update existing share rather than creating a duplicate
            var share = existing.get();
            share.setPermission(permission);
            share.setExpiresAt(expiresAt);
            share.setRevoked(false);
            return shareRepository.save(share);
        }

        // Create new share
        var share = new ShareEntity();
        share.setShareId(UUID.randomUUID().toString());
        share.setOwnerUsername(ownerUsername);
        share.setTargetUsername(targetUsername);
        share.setResourcePath(resourcePath);
        share.setPermission(permission);
        share.setExpiresAt(expiresAt);
        share.setRevoked(false);

        return shareRepository.save(share);
    }

    /**
     * List all active (non-revoked, non-expired) shares for a user.
     *
     * @param ownerUsername the username of the share owner
     * @return list of active shares
     */
    public List<ShareEntity> listActiveSharesByOwner(String ownerUsername) {
        return active(shareRepository.findByOwnerUsernameAndRevokedFalse(ownerUsername));
    }

    /**
     * List all active shares targeting a specific user (from any owner).
     *
     * @param targetUsername the username of the target user
     * @return list of active shares targeting this user
     */
    public List<ShareEntity> listActiveSharesByTarget(String targetUsername) {
        return active(shareRepository.findByTargetUsernameAndRevokedFalse(targetUsername));
    }

    /**
     * Revoke a share by its ID.
     *
     * @param shareId the share ID to revoke
     * @param ownerUsername the caller, who must be the share owner
     * @return true if the share was revoked, false if not found or not owned by the caller
     */
    @Transactional
    public boolean revokeShare(String shareId, String ownerUsername) {
        return shareRepository.findById(shareId)
                .filter(share -> share.getOwnerUsername().equals(ownerUsername))
                .map(share -> {
                    share.setRevoked(true);
                    shareRepository.save(share);
                    return true;
                })
                .orElse(false);
    }

    /**
     * True when {@code username} holds an active share for exactly this path.
     * Owners are handled by the caller; this only answers the share question.
     */
    public boolean hasShareAccess(String path, String username) {
        return active(shareRepository.findByTargetUsernameAndRevokedFalse(username)).stream()
                .anyMatch(share -> share.getResourcePath().equals(path));
    }

    /** Drops expired rows in Java because the DB query cannot express "null or future". */
    private List<ShareEntity> active(List<ShareEntity> shares) {
        Instant now = Instant.now();
        return shares.stream()
                .filter(s -> s.getExpiresAt() == null || s.getExpiresAt().isAfter(now))
                .toList();
    }

    /**
     * Generate a share token for the given share ID.
     * In a production system, this would be a signed JWT or unguessable UUID.
     *
     * @param shareId the share identifier
     * @return the share token
     */
    public String generateShareToken(String shareId) {
        return shareId;
    }
}
