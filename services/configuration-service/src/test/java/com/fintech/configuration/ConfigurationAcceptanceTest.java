package com.fintech.configuration;

/**
 * Acceptance criteria — configuration-service
 *
 * AC-1  GET /api/v1/config/vat_rate → 200 with seeded active param
 * AC-2  POST /api/v1/config → 201 with PENDING_APPROVAL status
 * AC-3  PUT /api/v1/config/{id}/approve → 200 with ACTIVE status
 * AC-4  GET /api/v1/config/{key}/history → 200 with list
 * AC-5  No token → 401
 */
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ConfigurationAcceptanceTest {

    // PostgreSQL is provided via Testcontainers JDBC URL in application-test.properties

    @Autowired TestRestTemplate restTemplate;

    @Value("${fintech.configuration.jwt-secret}")
    private String jwtSecret;

    @Test
    void ac1_getActiveVatRate_returns200WithSeededParam() {
        var resp = getJson("/api/v1/config/vat_rate", buildAuthHeader());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("paramKey");
        assertThat(resp.getBody().get("paramKey")).isEqualTo("vat_rate");
        assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void ac2_createParam_returns201WithPendingApproval() {
        var body = Map.of(
                "paramKey", "bureau_retry_max",
                "value", "3",
                "effectiveDate", LocalDate.now().toString());

        var resp = postJson("/api/v1/config", body, buildAuthHeader());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsEntry("status", "PENDING_APPROVAL");
        assertThat(resp.getBody()).containsKey("id");
    }

    @Test
    void ac3_approveParam_returns200WithActive() {
        // Create a new param first
        var body = Map.of("paramKey", "test_approve_key", "value", "42");
        var createResp = postJson("/api/v1/config", body, buildAuthHeader());
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String paramId = createResp.getBody().get("id").toString();
        var approveResp = putJson("/api/v1/config/" + paramId + "/approve", buildAuthHeader());

        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approveResp.getBody()).containsEntry("status", "ACTIVE");
    }

    @Test
    void ac4_getHistory_returns200WithList() {
        var resp = restTemplate.exchange(
                "/api/v1/config/vat_rate/history", HttpMethod.GET,
                new HttpEntity<>(buildAuthHeader()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void ac5_noToken_returns401() {
        var resp = getJson("/api/v1/config/vat_rate", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> getJson(String path, HttpHeaders headers) {
        HttpHeaders h = headers != null ? headers : new HttpHeaders();
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(h), new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Object body, HttpHeaders headers) {
        HttpHeaders h = headers != null ? headers : new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, h), new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> putJson(String path, HttpHeaders headers) {
        HttpHeaders h = headers != null ? headers : new HttpHeaders();
        return restTemplate.exchange(path, HttpMethod.PUT,
                new HttpEntity<>(h), new ParameterizedTypeReference<>() {});
    }

    private HttpHeaders buildAuthHeader() {
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("ADMIN"))
                .signWith(key)
                .compact();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
