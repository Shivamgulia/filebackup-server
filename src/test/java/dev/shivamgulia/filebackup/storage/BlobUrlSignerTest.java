package dev.shivamgulia.filebackup.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlobUrlSignerTest {

    private final BlobUrlSigner signer = new BlobUrlSigner("test-secret-0123456789abcdef0123456789abcdef");

    private static final String KEY = "aa".repeat(32);

    @Test
    void signedUrlRoundTrips() {
        String url = signer.presign(KEY);
        assertThat(url).startsWith("/api/blob?key=" + KEY).contains("&expires=").contains("&signature=");

        String[] parts = url.replace("/api/blob?", "").split("&");
        String expires = parts[1].substring("expires=".length());
        String signature = parts[2].substring("signature=".length());

        assertThat(signer.verify(KEY, expires, signature)).isTrue();
    }

    @Test
    void tamperedSignatureIsRejected() {
        long expires = System.currentTimeMillis() + 60_000;
        assertThat(signer.verify(KEY, String.valueOf(expires), "00".repeat(32))).isFalse();
        assertThat(signer.verify(KEY, String.valueOf(expires), signer.sign("other", expires))).isFalse();
    }

    @Test
    void expiredUrlIsRejected() {
        long expired = System.currentTimeMillis() - 1_000;
        assertThat(signer.verify(KEY, String.valueOf(expired), signer.sign(KEY, expired))).isFalse();
    }

    @Test
    void nonHashKeyIsRejected() {
        long expires = System.currentTimeMillis() + 60_000;
        assertThat(signer.verify("../etc/passwd", String.valueOf(expires), signer.sign("../etc/passwd", expires))).isFalse();
        assertThat(signer.verify("SHORT", String.valueOf(expires), signer.sign("SHORT", expires))).isFalse();
    }

    @Test
    void garbageInputIsRejectedNotThrown() {
        assertThat(signer.verify(KEY, "not-a-number", "sig")).isFalse();
        assertThat(signer.verify(null, "123", "sig")).isFalse();
        assertThat(signer.verify(KEY, null, "sig")).isFalse();
    }
}
