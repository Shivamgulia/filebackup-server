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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end backup cycle against the test profile (Postgres + local blob dir), authenticated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class FileBackupApiTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void uploadListDownloadDeleteCycle() {
        HttpHeaders auth = bearer();

        String path = "tester/api-test/hello.txt";
        byte[] content = "hello filebackup".getBytes();

        // upload → 201 with version 1
        ResponseEntity<Map> up = put(auth, path, content);
        assertThat(up.getStatusCode().value()).isEqualTo(201);
        assertThat(up.getBody()).containsEntry("version", 1);

        // re-upload same bytes → new version row (bytes are overwritten in place, one copy)
        assertThat(put(auth, path, content).getBody()).containsEntry("version", 2);

        // download latest → exact bytes back
        ResponseEntity<byte[]> down = get(auth, "/api/files/" + path, byte[].class);
        assertThat(down.getStatusCode().value()).isEqualTo(200);
        assertThat(down.getBody()).isEqualTo(content);

        // download a specific version → 404: only the latest version's bytes are stored
        assertThat(get(auth, "/api/files/" + path + "?version=1", byte[].class).getStatusCode().value()).isEqualTo(404);

        // list shows one active file with 2 versions
        ResponseEntity<Map[]> list = get(auth, "/api/files?prefix=tester/api-test/", Map[].class);
        assertThat(list.getBody()).hasSize(1);
        assertThat(list.getBody()[0]).containsEntry("path", path);
        assertThat(list.getBody()[0]).containsEntry("versionCount", 2);

        // HEAD exposes the checksum + version for cheap client diffing
        ResponseEntity<Void> head = rest.exchange("/api/files/" + path, HttpMethod.HEAD, new HttpEntity<Void>(auth), Void.class);
        assertThat(head.getStatusCode().value()).isEqualTo(200);
        assertThat(head.getHeaders().getFirst("X-Version")).isEqualTo("2");

        // delete → tombstone; download and list no longer see it
        assertThat(rest.exchange("/api/files/" + path, HttpMethod.DELETE, new HttpEntity<Void>(auth), Void.class)
                .getStatusCode().value()).isEqualTo(204);
        assertThat(get(auth, "/api/files/" + path, byte[].class).getStatusCode().value()).isEqualTo(404);
        assertThat(get(auth, "/api/files?prefix=tester/api-test/", Map[].class).getBody()).isEmpty();

        // uploading again re-activates the tombstoned path
        assertThat(put(auth, path, content).getBody()).containsEntry("version", 3);
    }

    @Test
    void invalidPathsAreRejected() {
        // Only "noprefix.txt" reaches the server deterministically: HTTP clients and containers
        // normalize or reject the other shapes ("//abs", "a/../b") before our 400 logic runs.
        // All path rules (absolute, traversal, //, no-prefix, bad chars) are covered in
        // BackupServicePathTest against requirePath directly.
        assertThat(put(bearer(), "noprefix.txt", "x".getBytes()).getStatusCode().value()).isEqualTo(400);
    }

    private HttpHeaders bearer() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login("tester", "testerpass"));
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

    private ResponseEntity<Map> put(HttpHeaders auth, String path, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(auth);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return rest.exchange("/api/files/" + path, HttpMethod.PUT,
                new HttpEntity<>(new ByteArrayResource(content), headers), Map.class);
    }

    private <T> ResponseEntity<T> get(HttpHeaders auth, String url, Class<T> type) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<Void>(auth), type);
    }
}
