package dev.shivamgulia.filebackup.file;

import dev.shivamgulia.filebackup.storage.BlobUrlSigner;
import dev.shivamgulia.filebackup.storage.FileStorage;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;

/**
 * The local profile's presigned upload receiver: signature + expiry in the query string
 * ARE the authorization (no JWT). Stores the blob content-addressed; no version row yet —
 * that happens at /api/uploads/complete.
 */
@RestController
@Profile("!aws")
public class BlobController {

    private final FileStorage blobs;
    private final BlobUrlSigner signer;

    public BlobController(FileStorage blobs, BlobUrlSigner signer) {
        this.blobs = blobs;
        this.signer = signer;
    }

    @PutMapping("/api/blob")
    ResponseEntity<Void> upload(@RequestParam String key, @RequestParam String expires,
                                @RequestParam String signature, InputStream body) {
        if (signer.verify(key, expires, signature)) {
            blobs.store(body);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
