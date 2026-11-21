package dev.shivamgulia.filebackup.share;

import dev.shivamgulia.filebackup.auth.AuthProperties;
import dev.shivamgulia.filebackup.file.FileEntity;
import dev.shivamgulia.filebackup.file.FileRepository;
import dev.shivamgulia.filebackup.share.service.ShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/shares")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    @Operation(
            summary = "Create a share for a resource path",
            description = "The owner can share a file or folder with another user. Shares can be read-only or read-write, and optionally have an expiration date."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Share created successfully",
                    content = @Content(schema = @Schema(implementation = ShareResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input: owner doesn't own the path, missing required fields, or invalid permission"),
            @ApiResponse(responseCode = "404", description = "Resource path not found or owner not found")
    })
    @PostMapping
    public ResponseEntity<ShareResponse> createShare(
            Authentication authentication,
            @RequestParam @Parameter(description = "Username of the recipient user", required = true) String targetUsername,
            @RequestParam @Parameter(description = "Path of the file or folder to share", required = true) String resourcePath,
            @RequestParam(defaultValue = "read") @Parameter(description = "Permission type: 'read' or 'read_write'", required = false) String permission,
            @RequestParam(required = false) @Parameter(description = "ISO timestamp for expiration, e.g. 2026-12-31T23:59:59Z", required = false) String expiresAt) {

        String ownerUsername = authentication.getName();
        Instant expires = expiresAt != null ? Instant.parse(expiresAt) : null;

        var share = shareService.createShare(
                ownerUsername, targetUsername, resourcePath, permission, expires);

        var token = shareService.generateShareToken(share.getShareId());
        var response = new ShareResponse(
                share.getShareId(),
                token,
                share.getResourcePath(),
                share.getPermission(),
                share.getCreatedAt(),
                share.getExpiresAt(),
                share.isRevoked()
        );

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "List shares owned by the authenticated user",
            description = "Lists all shares where this user is the owner, that are active (not revoked, not expired)"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of active shares",
                    content = @Content(schema = @Schema(implementation = ShareEntity.class)))
    })
    @GetMapping
    public ResponseEntity<List<ShareEntity>> listMyShares(Authentication authentication) {
        String ownerUsername = authentication.getName();
        var shares = shareService.listActiveSharesByOwner(ownerUsername);
        return ResponseEntity.ok(shares);
    }

    @Operation(
            summary = "Revoke a share",
            description = "Revoke an existing share, immediately invalidating the recipient's access"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Share revoked successfully"),
            @ApiResponse(responseCode = "404", description = "Share not found")
    })
    @DeleteMapping("/{shareId}")
    public ResponseEntity<Void> revokeShare(Authentication authentication,
                                           @PathVariable @Parameter(description = "The share ID to revoke", required = true) String shareId) {
        boolean revoked = shareService.revokeShare(shareId, authentication.getName());
        if (!revoked) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    public String generateShareToken(String shareId) {
        return shareId;
    }

    public static class ShareResponse {
        private final String shareId;
        private final String token;
        private final String resourcePath;
        private final String permission;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final boolean revoked;

        public ShareResponse(String shareId, String token, String resourcePath, String permission,
                             Instant createdAt, Instant expiresAt, boolean revoked) {
            this.shareId = shareId;
            this.token = token;
            this.resourcePath = resourcePath;
            this.permission = permission;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.revoked = revoked;
        }

        public String getShareId() { return shareId; }
        public String getToken() { return token; }
        public String getResourcePath() { return resourcePath; }
        public String getPermission() { return permission; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public boolean isRevoked() { return revoked; }
    }
}
