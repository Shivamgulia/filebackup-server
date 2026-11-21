package dev.shivamgulia.filebackup.share;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for share access: expiry, HEAD parity, owner-only revoke. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ShareAccessTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void activeShareGrantsReadButNotRevoke() {
        String path = "tester/share-test/file.txt";
        HttpHeaders owner = bearer("tester", "testerpass");
        HttpHeaders other = bearer("other", "otherpass");

        upload(owner, path);
        String shareId = createShare(owner, path, null);

        // recipient can read and HEAD a shared file
        assertThat(get(other, path).getStatusCode().value()).isEqualTo(200);
        assertThat(rest.exchange("/api/files/" + path, HttpMethod.HEAD, new HttpEntity<Void>(other), Void.class)
                .getStatusCode().value()).isEqualTo(200);

        // only the owner may revoke
        assertThat(rest.exchange("/api/shares/" + shareId, HttpMethod.DELETE, new HttpEntity<Void>(other), Void.class)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(rest.exchange("/api/shares/" + shareId, HttpMethod.DELETE, new HttpEntity<Void>(owner), Void.class)
                .getStatusCode().value()).isEqualTo(204);

        // revoked share no longer grants access
        assertThat(get(other, path).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void expiredShareGrantsNothing() {
        String path = "tester/share-test/expired.txt";
        HttpHeaders owner = bearer("tester", "testerpass");
        HttpHeaders other = bearer("other", "otherpass");

        upload(owner, path);
        createShare(owner, path, "2000-01-01T00:00:00Z");

        assertThat(get(other, path).getStatusCode().value()).isEqualTo(404);
    }

    private String createShare(HttpHeaders auth, String path, String expiresAt) {
        String url = "/api/shares?targetUsername={t}&resourcePath={p}&permission=read";
        Object[] vars = expiresAt == null
                ? new Object[]{"other", path}
                : new Object[]{"other", path, expiresAt};
        ResponseEntity<Map> res = expiresAt == null
                ? rest.postForEntity(url, new HttpEntity<Void>(auth), Map.class, vars)
                : rest.postForEntity(url + "&expiresAt={e}", new HttpEntity<Void>(auth), Map.class, vars);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (String) res.getBody().get("shareId");
    }

    private void upload(HttpHeaders auth, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(auth);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Map> res = rest.exchange("/api/files/" + path, HttpMethod.PUT,
                new HttpEntity<>(new ByteArrayResource("shared".getBytes()), headers), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
    }

    private ResponseEntity<byte[]> get(HttpHeaders auth, String path) {
        return rest.exchange("/api/files/" + path, HttpMethod.GET, new HttpEntity<Void>(auth), byte[].class);
    }

    private HttpHeaders bearer(String username, String password) {
        ResponseEntity<Map> res = rest.postForEntity("/api/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) res.getBody().get("token"));
        return headers;
    }
}
