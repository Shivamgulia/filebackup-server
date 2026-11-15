package dev.shivamgulia.filebackup.auth;

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

import static org.assertj.core.api.Assertions.assertThat;

/** Auth + ownership enforcement + health (exempt from auth). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class FileBackupAuthTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void loginRejectsBadCredentials() {
        assertThat(rest.postForEntity("/api/auth/login",
                Map.of("username", "tester", "password", "wrong"), Map.class).getStatusCode().value()).isEqualTo(401);
        assertThat(rest.postForEntity("/api/auth/login",
                Map.of("username", "ghost", "password", "x"), Map.class).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void requestsWithoutTokenAreUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        ResponseEntity<Map> res = rest.exchange("/api/files/tester/x.txt", HttpMethod.PUT,
                new HttpEntity<>(new ByteArrayResource("x".getBytes()), headers), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void tamperedTokenIsUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not.a.jwt");
        assertThat(rest.exchange("/api/files", HttpMethod.GET, new HttpEntity<Void>(headers), Void.class)
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void usersCannotTouchOtherUsersPaths() {
        String testerToken = login("tester", "testerpass");
        String otherToken = login("other", "otherpass");

        // tester uploads into their own subtree
        assertThat(put(testerToken, "tester/private.txt", "mine".getBytes()).getStatusCode().value()).isEqualTo(201);

        // other cannot read, list, overwrite, or delete it — 404, not 403 (no path leaking)
        assertThat(get(otherToken, "/api/files/tester/private.txt", byte[].class).getStatusCode().value()).isEqualTo(404);
        assertThat(get(otherToken, "/api/files?prefix=tester/", Map[].class).getBody()).isEmpty();
        assertThat(put(otherToken, "tester/private.txt", "hijack".getBytes()).getStatusCode().value()).isEqualTo(404);
        assertThat(rest.exchange("/api/files/tester/private.txt", HttpMethod.DELETE,
                new HttpEntity<Void>(auth(otherToken)), Void.class).getStatusCode().value()).isEqualTo(404);

        // other's own subtree works
        assertThat(put(otherToken, "other/own.txt", "ok".getBytes()).getStatusCode().value()).isEqualTo(201);

        // admin can cross user boundaries
        String adminToken = login("admin", "adminpass");
        assertThat(get(adminToken, "/api/files/tester/private.txt", byte[].class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void headReturnsChecksumWithoutBody() throws Exception {
        byte[] content = "checksum me".getBytes();
        String testerToken = login("tester", "testerpass");
        put(testerToken, "tester/checksum.txt", content);

        ResponseEntity<Void> head = rest.exchange("/api/files/tester/checksum.txt", HttpMethod.HEAD,
                new HttpEntity<Void>(auth(testerToken)), Void.class);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        assertThat(head.getHeaders().getFirst("X-Checksum-Sha256")).isEqualTo(expected);
    }

    @Test
    void healthIsOpenAndReportsIntegrity() {
        put(login("tester", "testerpass"), "tester/healthy.txt", "intact".getBytes());
        ResponseEntity<Map> res = rest.getForEntity("/api/health", Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("status", "UP");
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String login(String username, String password) {
        ResponseEntity<Map> res;
        try {
            res = rest.postForEntity("/api/auth/login",
                    Map.of("username", username, "password", password), Map.class);
        } catch (ResourceAccessException e) {
            // retry once: keep-alive race on the first request against a reused server
            res = rest.postForEntity("/api/auth/login",
                    Map.of("username", username, "password", password), Map.class);
        }
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (String) res.getBody().get("token");
    }

    private ResponseEntity<Map> put(String token, String path, byte[] content) {
        HttpHeaders headers = auth(token);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return rest.exchange("/api/files/" + path, HttpMethod.PUT,
                new HttpEntity<>(new ByteArrayResource(content), headers), Map.class);
    }

    private <T> ResponseEntity<T> get(String token, String url, Class<T> type) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<Void>(auth(token)), type);
    }
}
