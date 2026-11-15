package dev.shivamgulia.filebackup.file;

import dev.shivamgulia.filebackup.metadata.FileSummary;
import dev.shivamgulia.filebackup.metadata.VersionRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The backup API. Paths are the full backup path incl. the username prefix ({*path} captures slashes).
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final BackupService backup;

    public FileController(BackupService backup) {
        this.backup = backup;
    }

    public record UploadResponse(String path, long version) {
    }

    @PutMapping("/{*path}")
    ResponseEntity<UploadResponse> upload(@PathVariable String path, InputStream body, Authentication auth) {
        String p = path.substring(1); // {*}path keeps the leading slash
        VersionRecord saved = backup.upload(p, auth.getName(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadResponse(saved.path(), saved.version()));
    }

    @GetMapping("/{*path}")
    ResponseEntity<StreamingResponseBody> download(@PathVariable String path, @RequestParam(required = false) Long version,
                                                   Authentication auth) {
        String p = path.substring(1);
        BackupService.Download download = backup.download(p, version, auth.getName());
        VersionRecord record = download.record();
        return ResponseEntity.ok()
                .header("X-Checksum-Sha256", record.sha256())
                .contentLength(record.sizeBytes())
                .body(verifyWhileStreaming(download));
    }

    @RequestMapping(value = "/{*path}", method = RequestMethod.HEAD)
    ResponseEntity<Void> head(@PathVariable String path, Authentication auth) {
        VersionRecord record = backup.latest(path.substring(1), auth.getName());
        return ResponseEntity.ok()
                .header("X-Checksum-Sha256", record.sha256())
                .header("X-Version", String.valueOf(record.version()))
                .contentLength(record.sizeBytes())
                .build();
    }

    @GetMapping
    List<FileSummary> list(@RequestParam(required = false) String prefix, Authentication auth) {
        return backup.list(prefix, auth.getName());
    }

    @DeleteMapping("/{*path}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String path, Authentication auth) {
        backup.delete(path.substring(1), auth.getName());
    }

    /** Streams the blob while computing sha256; a mismatch is logged (health endpoint reports corruption). */
    private StreamingResponseBody verifyWhileStreaming(BackupService.Download download) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return (OutputStream out) -> {
            try (InputStream in = new DigestInputStream(download.content(), sha256)) {
                in.transferTo(out);
            }
            String actual = HexFormat.of().formatHex(sha256.digest());
            if (!actual.equals(download.record().sha256())) {
                throw new UncheckedIOException(new IOException(
                        "Checksum mismatch for " + download.record().path() + " v" + download.record().version()));
            }
        };
    }

    @ExceptionHandler(UncheckedIOException.class)
    ResponseEntity<Void> corruptBlob(UncheckedIOException ex) {
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON).build();
    }
}
