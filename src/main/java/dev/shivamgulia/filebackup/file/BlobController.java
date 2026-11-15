package dev.shivamgulia.filebackup.file;

import dev.shivamgulia.filebackup.storage.BlobUrlSigner;
import dev.shivamgulia.filebackup.storage.FileStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The local profile's presigned upload receiver: signature + expiry in the query string
 * ARE the authorization (no JWT). Stores the bytes at the signed key (= the file's backup
 * path); no version row yet — that happens at /api/uploads/complete.
 */
@RestController
@Profile("!aws")
public class BlobController {

    private final FileStorage storage;
    private final BlobUrlSigner signer;

    public BlobController(FileStorage storage, BlobUrlSigner signer) {
        this.storage = storage;
        this.signer = signer;
    }

    /**
     * Params are parsed from the query string manually: resolving {@code @RequestParam} would
     * call getParameter, which makes Tomcat consume a form-encoded body and leave the stream empty.
     */
    @PutMapping("/api/blob")
    ResponseEntity<Void> upload(HttpServletRequest request, InputStream body) throws IOException {
        Map<String, String> q = new HashMap<>();
        String query = request.getQueryString();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                q.put(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        if (signer.verify(q.get("key"), q.get("expires"), q.get("signature"))) {
            storage.store(URLDecoder.decode(q.get("key"), StandardCharsets.UTF_8), body);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
