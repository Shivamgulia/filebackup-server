package dev.shivamgulia.filebackup.file;

import dev.shivamgulia.filebackup.metadata.VersionRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * The presigned upload API (HLD §3.2a): initiate returns either a committed version
 * (dedup) or an absolute signed uploadUrl; complete verifies and commits.
 * The client never branches on profile — it follows the response.
 */
@RestController
public class UploadController {

    public record InitiateRequest(String path, String sha256, long sizeBytes) {
    }

    /** status: "committed" (version set) or "presigned" (uploadUrl + method set). */
    public record InitiateResponse(String status, String uploadUrl, String method, Long version) {
    }

    public record CompleteRequest(String path, String sha256, long sizeBytes) {
    }

    public record CompleteResponse(String path, long version) {
    }

    private final BackupService backup;

    public UploadController(BackupService backup) {
        this.backup = backup;
    }

    @PostMapping("/api/uploads")
    ResponseEntity<InitiateResponse> initiate(@RequestBody InitiateRequest request, Authentication auth) {
        BackupService.Initiated initiated = backup.initiate(request.path(), auth.getName(), request.sha256(), request.sizeBytes());
        if ("committed".equals(initiated.status())) {
            return ResponseEntity.ok(new InitiateResponse("committed", null, null, initiated.version()));
        }
        String absolute = absolutize(initiated.uploadUrl());
        return ResponseEntity.ok(new InitiateResponse("presigned", absolute, initiated.method(), null));
    }

    @PostMapping("/api/uploads/complete")
    ResponseEntity<CompleteResponse> complete(@RequestBody CompleteRequest request, Authentication auth) {
        VersionRecord record = backup.complete(request.path(), auth.getName(), request.sha256(), request.sizeBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CompleteResponse(record.path(), record.version()));
    }

    /** Presigned URLs must be absolute for remote clients; S3 URLs already are. */
    private static String absolutize(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(URI.create(url).getRawPath() + "?" + URI.create(url).getRawQuery())
                .build().toUriString();
    }
}
