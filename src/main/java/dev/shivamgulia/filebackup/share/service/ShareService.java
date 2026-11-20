package dev.shivamgulia.filebackup.share.service;

import dev.shivamgulia.filebackup.auth.AuthProperties;
import dev.shivamgulia.filebackup.file.FileEntity;
import dev.shivamgulia.filebackup.file.FileRepository;
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
    private final FileRepository fileRepository;
    private final AuthProperties authProperties;

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
        var existing = shareRepository.findByOwnerUsernameAndTargetUsernameAndResourcePathAndRevokedFalseAndExpiresAtIsNull(
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
        return shareRepository.findByOwnerUsernameAndRevokedFalseAndExpiresAtIsNull(ownerUsername);
    }

    /**
     * List all active shares targeting a specific user (from any owner).
     *
     * @param targetUsername the username of the target user
     * @return list of active shares targeting this user
     */
    public List<ShareEntity> listActiveSharesByTarget(String targetUsername) {
        return shareRepository.findByTargetUsernameAndRevokedFalseAndExpiresAtIsNull(targetUsername);
    }

    /**
     * Revoke a share by its ID.
     *
     * @param shareId the share ID to revoke
     * @return true if the share was revoked, false if not found
     */
    @Transactional
    public boolean revokeShare(String shareId) {
        var optional = shareRepository.findById(shareId);
        if (optional.isPresent()) {
            var share = optional.get();
            share.setRevoked(true);
            shareRepository.save(share);
            return true;
        }
        return false;
    }

    /**
     * Check if a user has access to a specific resource path.
     * Access is granted if:
     * 1. The user is the owner of the path, OR
     * 2. The user has an active (non-revoked, non-expired) share for this path
     *
     * @param path the resource path being accessed
     * @param username the username of the requesting user
     * @return true if access is granted, false otherwise
     */
    public boolean hasShareAccess(String path, String username) {
        // First, check if the user is the owner (existing behavior)
        String ownerOfPath = path.split("/")[0];
        if (ownerOfPath.equals(username)) {
            return true;
        }

        // Check if the user has an active share for this path
        var shares = shareRepository.findByTargetUsernameAndRevokedFalseAndExpiresAtIsNull(username);
        return shares.stream()
                .filter(s -> s.getResourcePath().equals(path))
                .noneMatch(s -> s.getExpiresAt() != null && s.getExpiresAt().isBefore(Instant.now()));
    }

    /**
     * Check if a share exists and is active for the given path and target user.
     *
     * @param path the resource path
     * @param targetUsername the username of the potential recipient
     * @return the active share, or null if not found/expired/revoked
     */
    public ShareEntity findActiveShareForPath(String path, String targetUsername) {
        return shareRepository.findByTargetUsernameAndRevokedFalseAndExpiresAtIsNull(targetUsername)
                .stream()
                .filter(s -> s.getResourcePath().equals(path))
                .filter(s -> s.getExpiresAt() == null || !s.getExpiresAt().isBefore(Instant.now()))
                .findFirst()
                .orElse(null);
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
