package dev.shivamgulia.filebackup.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlobUrlSignerTest {

    private final BlobUrlSigner signer = new BlobUrlSigner("test-secret-0123456789abcdef0123456789abcdef");

    private static final String PATH = "tester/photos/report.pdf";

    @Test
    void signedUrlRoundTripsWithAnEncodedPath() {
        String url = signer.presign(PATH);
        assertThat(url).startsWith("/api/blob?key=tester%2Fphotos%2Freport.pdf").contains("&expires=").contains("&signature=");

        String[] parts = url.replace("/api/blob?", "").split("&");
        String expires = parts[1].substring("expires=".length());
        String signature = parts[2].substring("signature=".length());

        assertThat(signer.verify(parts[0].substring("key=".length()), expires, signature)).isTrue();
        assertThat(signer.verify(PATH, expires, signature)).isTrue(); // raw path also accepted
    }

    @Test
    void tamperedSignatureIsRejected() {
        long expires = System.currentTimeMillis() + 60_000;
        assertThat(signer.verify(PATH, String.valueOf(expires), "00".repeat(32))).isFalse();
        assertThat(signer.verify(PATH, String.valueOf(expires), signer.sign("other", expires))).isFalse();
    }

    @Test
    void expiredUrlIsRejected() {
        long expired = System.currentTimeMillis() - 1_000;
        assertThat(signer.verify(PATH, String.valueOf(expired), signer.sign(PATH, expired))).isFalse();
    }

    @Test
    void unsafeKeyIsRejected() {
        long expires = System.currentTimeMillis() + 60_000;
        assertThat(signer.verify("../etc/passwd", String.valueOf(expires), signer.sign("../etc/passwd", expires))).isFalse();
        assertThat(signer.verify("tester%2F..%2Fescape.txt", String.valueOf(expires), signer.sign("tester/../escape.txt", expires))).isFalse();
        assertThat(signer.verify("/absolute", String.valueOf(expires), signer.sign("/absolute", expires))).isFalse();
        assertThat(signer.verify("has space", String.valueOf(expires), signer.sign("has space", expires))).isFalse();
    }

    @Test
    void garbageInputIsRejectedNotThrown() {
        assertThat(signer.verify(PATH, "not-a-number", "sig")).isFalse();
        assertThat(signer.verify(null, "123", "sig")).isFalse();
        assertThat(signer.verify(PATH, null, "sig")).isFalse();
    }
}
