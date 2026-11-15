package dev.shivamgulia.filebackup.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Signs and verifies the local profile's presigned upload URLs:
 * {@code /api/blob?key=<sha256>&expires=<epoch-millis>&signature=<hex>},
 * with signature = HMAC-SHA256(jwt-secret, key + ":" + expires). The self-hosted
 * equivalent of an S3 presigned URL.
 */
@Component
@Profile("!aws")
public class BlobUrlSigner {

    public static final Duration EXPIRY = Duration.ofMinutes(15);

    private final byte[] secret;

    public BlobUrlSigner(@Value("${filebackup.jwt-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    String sign(String blobKey, long expiresAtMillis) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((blobKey + ":" + expiresAtMillis).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public String presign(String blobKey) {
        long expires = System.currentTimeMillis() + EXPIRY.toMillis();
        return "/api/blob?key=" + blobKey + "&expires=" + expires + "&signature=" + sign(blobKey, expires);
    }

    /** False when the signature is wrong, expired, malformed, or the key is not a sha256 hex string. */
    public boolean verify(String key, String expiresParam, String signature) {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            return false;
        }
        try {
            long expires = Long.parseLong(expiresParam);
            return MessageDigest.isEqual(
                    sign(key, expires).getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII))
                    && Instant.now().toEpochMilli() < expires;
        } catch (NumberFormatException | NullPointerException e) {
            return false;
        }
    }
}
