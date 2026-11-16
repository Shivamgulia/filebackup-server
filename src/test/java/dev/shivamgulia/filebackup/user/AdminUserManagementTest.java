package dev.shivamgulia.filebackup.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin user management: /api/admin/** is ROLE_ADMIN only; suspension kills logins and live tokens. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class AdminUserManagementTest {

    @Autowired
    TestRestTemplate rest;

    private String adminToken() {
        return login("admin", "adminpass");
    }

    private String login(String username, String password) {
        ResponseEntity<Map> res = rest.postForEntity("/api/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return (String) res.getBody().get("token");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<Map> adminPost(String url, String token, Map<String, ?> body) {
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, bearer(token)), Map.class);
    }

    private ResponseEntity<Void> adminVoid(String url, String token, HttpMethod method, Map<String, ?> body) {
        return rest.exchange(url, method, new HttpEntity<>(body, bearer(token)), Void.class);
    }

    private ResponseEntity<Void> adminPut(String url, String token, Map<String, ?> body) {
        return adminVoid(url, token, HttpMethod.PUT, body);
    }

    @Test
    void nonAdminGets403OnEveryAdminRoute() {
        String token = login("tester", "testerpass");
        assertThat(rest.exchange("/api/admin/users", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), Map.class).getStatusCode().value()).isEqualTo(403);
        assertThat(adminPost("/api/admin/users", token,
                Map.of("username", "x", "password", "password123")).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void adminCreatesListsAndDuplicatesAre409() {
        String token = adminToken();
        String name = "newuser-" + System.nanoTime();

        ResponseEntity<Map> created = adminPost("/api/admin/users", token,
                Map.of("username", name, "password", "password123"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        assertThat(adminPost("/api/admin/users", token,
                Map.of("username", name, "password", "password123")).getStatusCode().value()).isEqualTo(409);

        // validation is enforced at the boundary
        assertThat(adminPost("/api/admin/users", token,
                Map.of("username", "bad user!", "password", "password123")).getStatusCode().value()).isEqualTo(400);
        assertThat(adminPost("/api/admin/users", token,
                Map.of("username", "okname", "password", "short")).getStatusCode().value()).isEqualTo(400);

        var list = (java.util.List<?>) rest.exchange("/api/admin/users", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), java.util.List.class).getBody();
        assertThat((java.util.List<?>) list).anySatisfy(u ->
                assertThat(((Map<?, ?>) u).get("username")).isEqualTo(name));

        // the created user can actually log in
        assertThat(login(name, "password123")).isNotBlank();
    }

    @Test
    void passwordChangeTakesEffect() {
        String token = adminToken();
        String name = "pwuser-" + System.nanoTime();
        adminPost("/api/admin/users", token, Map.of("username", name, "password", "password123"));

        assertThat(adminVoid("/api/admin/users/" + name + "/password", token, HttpMethod.POST,
                Map.of("password", "newpassword9")).getStatusCode().value()).isEqualTo(204);
        assertThat(login(name, "newpassword9")).isNotBlank();
        assertThat(rest.postForEntity("/api/auth/login",
                Map.of("username", name, "password", "password123"), Map.class).getStatusCode().value()).isEqualTo(401);

        // too-short new password rejected
        assertThat(adminVoid("/api/admin/users/" + name + "/password", token, HttpMethod.POST,
                Map.of("password", "short")).getStatusCode().value()).isEqualTo(400);
        // unknown user → 404
        assertThat(adminPost("/api/admin/users/ghost-" + name + "/password", token,
                Map.of("password", "newpassword9")).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void suspensionBlocksLoginAndKillsLiveTokens() {
        String token = adminToken();
        String name = "sususer-" + System.nanoTime();
        adminPost("/api/admin/users", token, Map.of("username", name, "password", "password123"));
        String userToken = login(name, "password123");
        assertThat(rest.exchange("/api/files?prefix=" + name + "/", HttpMethod.GET,
                new HttpEntity<>(bearer(userToken)), String.class).getStatusCode().value()).isEqualTo(200);

        assertThat(adminPut("/api/admin/users/" + name + "/suspension", token,
                Map.of("suspended", true)).getStatusCode().value()).isEqualTo(204);

        // existing token no longer works (filter checks suspension per request)
        assertThat(rest.exchange("/api/files?prefix=" + name + "/", HttpMethod.GET,
                new HttpEntity<>(bearer(userToken)), String.class).getStatusCode().value()).isEqualTo(401);
        // and login is refused with 403
        ResponseEntity<Map> refused = rest.postForEntity("/api/auth/login",
                Map.of("username", name, "password", "password123"), Map.class);
        assertThat(refused.getStatusCode().value()).isEqualTo(403);
        assertThat(refused.getBody().get("message")).isEqualTo("Account suspended");

        // unsuspending restores access
        assertThat(adminPut("/api/admin/users/" + name + "/suspension", token,
                Map.of("suspended", false)).getStatusCode().value()).isEqualTo(204);
        assertThat(login(name, "password123")).isNotBlank();

        // unknown user → 404; admin itself cannot be suspended
        assertThat(adminPut("/api/admin/users/ghost-" + name + "/suspension", token,
                Map.of("suspended", true)).getStatusCode().value()).isEqualTo(404);
        assertThat(adminPut("/api/admin/users/admin/suspension", token,
                Map.of("suspended", true)).getStatusCode().value()).isEqualTo(400);
    }
}
