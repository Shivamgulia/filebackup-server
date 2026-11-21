package dev.shivamgulia.filebackup.share;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ShareEntity operations.
 */
public interface ShareRepository extends JpaRepository<ShareEntity, String> {

    /** Dedupe lookup for re-sharing the same path with the same recipient. */
    Optional<ShareEntity> findFirstByOwnerUsernameAndTargetUsernameAndResourcePathAndRevokedFalse(
            String ownerUsername, String targetUsername, String resourcePath);

    /** All non-revoked shares owned by a user (expiry is filtered in the service). */
    List<ShareEntity> findByOwnerUsernameAndRevokedFalse(String ownerUsername);

    /** All non-revoked shares targeting a user (expiry is filtered in the service). */
    List<ShareEntity> findByTargetUsernameAndRevokedFalse(String targetUsername);
}
