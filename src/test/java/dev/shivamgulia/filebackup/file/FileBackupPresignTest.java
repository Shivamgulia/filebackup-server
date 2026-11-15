package dev.shivamgulia.filebackup.file;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.ResourceAccessException;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The presigned upload flow (HLD §3.2a) against the local profile: initiate → PUT blob → complete. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class FileBackupPresignTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void presignedCycleStoresAndVersions() throws Exception {
        HttpHeaders auth = bearer();
        // unique content per run: ./data/blobs survives between runs even when the test DB doesn't,
        // so a fixed hash would dedup-commit on the first initiate instead of presigning
        String path = "tester/presign/report.pdf";
        byte[] content = ("presigned content " + UUID.randomUUID()).getBytes();
        String sha256 = sha256Of(content);

        // initiate → presigned URL
        ResponseEntity<Map> init = post(auth, "/api/uploads",
                Map.of("path", path, "sha256", sha256, "sizeBytes", content.length));
        assertThat(init.getStatusCode().value()).isEqualTo(200);
        assertThat(init.getBody()).containsEntry("status", "presigned");
        String uploadUrl = (String) init.getBody().get("uploadUrl");
        assertThat(uploadUrl).startsWith("http://").contains("/api/blob?");

        // PUT bytes to the signed URL (no JWT — the signature is the auth)
        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(rest.exchange(uploadUrl, HttpMethod.PUT, new HttpEntity<>(new ByteArrayResource(content), putHeaders), Void.class)
                .getStatusCode().value()).isEqualTo(204);

        // complete → version committed
        ResponseEntity<Map> done = post(auth, "/api/uploads/complete",
                Map.of("path", path, "sha256", sha256, "sizeBytes", content.length));
        assertThat(done.getStatusCode().value()).isEqualTo(201);
        assertThat(done.getBody()).containsEntry("version", 1);

        // download via the normal path returns exact bytes
        ResponseEntity<byte[]> down = rest.exchange("/api/files/" + path, HttpMethod.GET, new HttpEntity<Void>(auth), byte[].class);
        assertThat(down.getBody()).isEqualTo(content);

        // re-initiate with same hash → committed (dedup), no upload needed
        ResponseEntity<Map> again = post(auth, "/api/uploads",
                Map.of("path", path, "sha256", sha256, "sizeBytes", content.length));
        assertThat(again.getBody()).containsEntry("status", "committed");
        assertThat(again.getBody()).containsEntry("version", 2);
    }

    @Test
    void wrongContentUnderClaimedHashIsRejected() throws Exception {
        HttpHeaders auth = bearer();
        String path = "tester/presign/lie.pdf";
        String claimed = sha256Of("honest".getBytes());
        byte[] dishonest = ("dishonest " + UUID.randomUUID()).getBytes();

        String url = (String) post(auth, "/api/uploads",
                Map.of("path", path, "sha256", claimed, "sizeBytes", dishonest.length)).getBody().get("uploadUrl");

        HttpHeaders putHeaders = new HttpHeaders();
        putHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(new ByteArrayResource(dishonest), putHeaders), Void.class);

        // the claimed key does not exist (bytes stored under their true hash) → 409
        assertThat(post(auth, "/api/uploads/complete",
                Map.of("path", path, "sha256", claimed, "sizeBytes", dishonest.length)).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void completeWithoutUploadIsConflict() throws Exception {
        HttpHeaders auth = bearer();
        String sha256 = sha256Of(("never-uploaded " + UUID.randomUUID()).getBytes());
        assertThat(post(auth, "/api/uploads/complete",
                Map.of("path", "tester/presign/ghost.pdf", "sha256", sha256, "sizeBytes", 13)).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void tamperedBlobUrlIsUnauthorized() throws Exception {
        HttpHeaders auth = bearer();
        String sha256 = sha256Of(("sig-test " + UUID.randomUUID()).getBytes());
        String url = (String) post(auth, "/api/uploads",
                Map.of("path", "tester/presign/sig.pdf", "sha256", sha256, "sizeBytes", 40)).getBody().get("uploadUrl");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(rest.exchange(url + "tampered", HttpMethod.PUT, new HttpEntity<>(new ByteArrayResource("x".getBytes()), headers), Void.class)
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void initiateEnforcesOwnership() throws Exception {
        HttpHeaders other = bearer("other", "otherpass");
        String sha256 = sha256Of(("nope " + UUID.randomUUID()).getBytes());
        assertThat(post(other, "/api/uploads",
                Map.of("path", "tester/not-mine.pdf", "sha256", sha256, "sizeBytes", 4)).getStatusCode().value()).isEqualTo(404);
    }

    private static String sha256Of(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private HttpHeaders bearer() {
        return bearer("tester", "testerpass");
    }

    private HttpHeaders bearer(String user, String pass) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login(user, pass));
        return headers;
    }

    private String login(String username, String password) {
        ResponseEntity<Map> res = rest.postForEntity("/api/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (String) res.getBody().get("token");
    }

    private ResponseEntity<Map> post(HttpHeaders auth, String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(auth);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        } catch (ResourceAccessException e) {
            // known flake: keep-alive race on the first request of a reused test server — retry once
            return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        }
    }
}
