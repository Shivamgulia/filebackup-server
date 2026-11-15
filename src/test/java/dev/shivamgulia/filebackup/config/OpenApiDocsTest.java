package dev.shivamgulia.filebackup.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Docs are public and describe the real API — no JWT needed to read them. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class OpenApiDocsTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void apiDocsArePublicAndDescribeTheApi() {
        ResponseEntity<String> res = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains("FileBackup").contains("/api/uploads/complete").contains("bearer");
    }

    @Test
    void swaggerUiIsServed() {
        assertThat(rest.getForEntity("/swagger-ui/index.html", String.class).getStatusCode().value()).isEqualTo(200);
    }
}
