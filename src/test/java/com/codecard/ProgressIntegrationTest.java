package com.codecard;

import com.codecard.auth.dto.*;
import com.codecard.progress.dto.ProgressSyncRequest;
import com.codecard.progress.dto.ProgressSyncResponse;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProgressIntegrationTest {

    private static ConfigurableApplicationContext context;
    private static int port;
    private static RestTemplate restTemplate;

    private static String accessToken;
    private static final String TEST_EMAIL = "test-progress-" + System.currentTimeMillis() + "@codecard.app";

    @BeforeAll
    static void startApp() {
        context = SpringApplication.run(CodeCardApplication.class,
                "--server.port=0",
                "--spring.sql.init.mode=never",
                "--spring.datasource.url=jdbc:h2:mem:codecard-test-progress",
                "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "--spring.mail.host=localhost",
                "--spring.mail.port=25",
                "--jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLW1pbmltdW0tMjU2LWJpdHM=",
                "--jwt.access-expiration-ms=900000",
                "--jwt.refresh-expiration-ms=2592000000");
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();

        restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            public boolean hasError(ClientHttpResponse response) { return false; }
            public void handleError(ClientHttpResponse response) {}
        });

        // Register a test user
        var registerReq = new RegisterRequest();
        registerReq.setEmail(TEST_EMAIL);
        registerReq.setPassword("test123456");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var resp = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/register",
                new HttpEntity<>(registerReq, headers), AuthResponse.class);

        accessToken = resp.getBody().getAccessToken();
    }

    @AfterAll
    static void stopApp() {
        if (context != null) context.close();
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/v1" + path;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @Order(1)
    void getProgress_empty_shouldReturnEmptyResponse() {
        ResponseEntity<ProgressSyncResponse> resp = restTemplate.exchange(
                url("/progress"), HttpMethod.GET,
                new HttpEntity<>(authHeaders()), ProgressSyncResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData()).isEmpty();
        assertThat(resp.getBody().getVersion()).isEqualTo(3);
    }

    @Test
    @Order(2)
    void syncProgress_noRemoteData_shouldSaveAndReturnMergedFalse() {
        ProgressSyncRequest req = new ProgressSyncRequest();
        req.setData(Map.of("course1", Map.of("xp", 100), "course2", Map.of("xp", 200)));
        req.setVersion(3);

        ResponseEntity<ProgressSyncResponse> resp = restTemplate.postForEntity(
                url("/progress/sync"), new HttpEntity<>(req, authHeaders()), ProgressSyncResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().isMerged()).isFalse();
        assertThat(resp.getBody().getData()).containsKeys("course1", "course2");
    }

    @Test
    @Order(3)
    void syncProgress_hasRemoteData_shouldReturnRemoteWithMergedTrue() {
        ProgressSyncRequest req = new ProgressSyncRequest();
        req.setData(Map.of("course3", Map.of("xp", 999)));
        req.setVersion(4);

        ResponseEntity<ProgressSyncResponse> resp = restTemplate.postForEntity(
                url("/progress/sync"), new HttpEntity<>(req, authHeaders()), ProgressSyncResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().isMerged()).isTrue();
        assertThat(resp.getBody().getData()).containsKeys("course1", "course2");
    }

    @Test
    @Order(4)
    void upsertProgress_shouldOverwrite() {
        ProgressSyncRequest req = new ProgressSyncRequest();
        req.setData(Map.of("courseA", Map.of("xp", 500)));
        req.setVersion(5);

        ResponseEntity<ProgressSyncResponse> resp = restTemplate.exchange(
                url("/progress"), HttpMethod.PUT,
                new HttpEntity<>(req, authHeaders()), ProgressSyncResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData()).containsKeys("courseA");
        assertThat(resp.getBody().getData()).doesNotContainKeys("course1", "course2");
        assertThat(resp.getBody().getVersion()).isEqualTo(5);
    }
}
