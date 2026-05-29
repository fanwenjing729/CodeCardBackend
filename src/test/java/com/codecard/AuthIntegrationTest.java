package com.codecard;

import com.codecard.auth.dto.*;
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
class AuthIntegrationTest {

    private static ConfigurableApplicationContext context;
    private static int port;
    private static RestTemplate restTemplate;

    private static String accessToken;
    private static String refreshToken;
    private static final String TEST_EMAIL = "test-auth-" + System.currentTimeMillis() + "@codecard.app";
    private static final String TEST_PASSWORD = "test123456";

    @BeforeAll
    static void startApp() {
        context = SpringApplication.run(CodeCardApplication.class,
                "--server.port=0",
                "--spring.sql.init.mode=never",
                "--spring.datasource.url=jdbc:h2:mem:codecard-test-auth",
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
    }

    @AfterAll
    static void stopApp() {
        if (context != null) context.close();
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/v1" + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = jsonHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return headers;
    }

    @Test
    @Order(1)
    void register_shouldReturnTokens() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail(TEST_EMAIL);
        req.setPassword(TEST_PASSWORD);

        ResponseEntity<AuthResponse> resp = restTemplate.postForEntity(
                url("/auth/register"), new HttpEntity<>(req, jsonHeaders()), AuthResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getAccessToken()).isNotBlank();
        assertThat(resp.getBody().getRefreshToken()).isNotBlank();
        assertThat(resp.getBody().getIsNewUser()).isTrue();

        accessToken = resp.getBody().getAccessToken();
        refreshToken = resp.getBody().getRefreshToken();
    }

    @Test
    @Order(2)
    void registerDuplicateEmail_shouldReturn401() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail(TEST_EMAIL);
        req.setPassword(TEST_PASSWORD);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/auth/register"), new HttpEntity<>(req, jsonHeaders()), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(3)
    void login_withCorrectCredentials_shouldReturnTokens() {
        LoginRequest req = new LoginRequest();
        req.setEmail(TEST_EMAIL);
        req.setPassword(TEST_PASSWORD);

        ResponseEntity<AuthResponse> resp = restTemplate.postForEntity(
                url("/auth/login"), new HttpEntity<>(req, jsonHeaders()), AuthResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getAccessToken()).isNotBlank();
        assertThat(resp.getBody().getRefreshToken()).isNotBlank();

        accessToken = resp.getBody().getAccessToken();
        refreshToken = resp.getBody().getRefreshToken();
    }

    @Test
    @Order(4)
    void login_withWrongPassword_shouldReturn401() {
        LoginRequest req = new LoginRequest();
        req.setEmail(TEST_EMAIL);
        req.setPassword("wrongpassword");

        ResponseEntity<Map> resp = restTemplate.postForEntity(
                url("/auth/login"), new HttpEntity<>(req, jsonHeaders()), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(5)
    void getProfile_withValidToken_shouldReturnProfile() {
        ResponseEntity<AuthResponse.UserProfile> resp = restTemplate.exchange(
                url("/auth/me"), HttpMethod.GET, new HttpEntity<>(authHeaders()),
                AuthResponse.UserProfile.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getEmail()).isEqualTo(TEST_EMAIL);
    }

    @Test
    @Order(6)
    void getProfile_withoutToken_shouldReturn401() {
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/auth/me"), HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(7)
    void refresh_withAccessToken_shouldReturn401() {
        RefreshRequest req = new RefreshRequest();
        req.setRefreshToken(accessToken);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/auth/refresh"), new HttpEntity<>(req, jsonHeaders()), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(8)
    void refresh_withValidRefreshToken_shouldReturnNewTokens() {
        RefreshRequest req = new RefreshRequest();
        req.setRefreshToken(refreshToken);

        ResponseEntity<AuthResponse> resp = restTemplate.postForEntity(
                url("/auth/refresh"), new HttpEntity<>(req, jsonHeaders()), AuthResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getAccessToken()).isNotBlank();
        assertThat(resp.getBody().getRefreshToken()).isNotBlank();

        accessToken = resp.getBody().getAccessToken();
        refreshToken = resp.getBody().getRefreshToken();
    }

    @Test
    @Order(9)
    void logout_shouldClearRefreshTokens() {
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/auth/logout"), HttpMethod.POST, new HttpEntity<>(authHeaders()), String.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        // Trying to refresh with the old token should fail
        RefreshRequest req = new RefreshRequest();
        req.setRefreshToken(refreshToken);

        ResponseEntity<String> refreshResp = restTemplate.postForEntity(
                url("/auth/refresh"), new HttpEntity<>(req, jsonHeaders()), String.class);

        assertThat(refreshResp.getStatusCode().value()).isEqualTo(401);
    }
}
