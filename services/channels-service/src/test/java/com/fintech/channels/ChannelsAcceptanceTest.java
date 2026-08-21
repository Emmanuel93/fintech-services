package com.fintech.channels;

/**
 * Acceptance criteria — channels-service (HTTP end-to-end, real Postgres + Kafka)
 *
 * AC-1  POST /sessions no token                 → 401
 * AC-2  POST /sessions (MOBILE_APP)             → 201 ACTIVE
 * AC-3  GET  /sessions/{id}                     → 200
 * AC-4  PUT  /sessions/{id}/close               → 200 CLOSED
 * AC-5  POST /sessions/{id}/intents (CREDIT_APPLICATION) → 201
 * AC-6  GET  /sessions/{unknown}                → 404
 * AC-7  GET  /channels as ADMIN                 → 200
 */

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "channels.session-started", "channels.session-expired",
        "channels.intent-captured", "channels.intent-routed", "channels.intent-abandoned",
        "channels.application-started", "channels.lead-created", "channels.lead-converted"
})
class ChannelsAcceptanceTest {

    @Autowired TestRestTemplate restTemplate;

    private String startSession() {
        var resp = postJson("/api/v1/sessions", Map.of("channelType", "MOBILE_APP"), userHeaders());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("sessionId");
    }

    @Test
    void ac1_noToken_returns401() {
        var resp = postRaw("/api/v1/sessions", Map.of("channelType", "MOBILE_APP"), null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac2_startSession_returns201Active() {
        var resp = postJson("/api/v1/sessions", Map.of("channelType", "MOBILE_APP"), userHeaders());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
        assertThat(resp.getBody().get("channelType")).isEqualTo("MOBILE_APP");
    }

    @Test
    void ac3_getSession_returns200() {
        String sessionId = startSession();
        var resp = getJson("/api/v1/sessions/" + sessionId, userHeaders());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("sessionId")).isEqualTo(sessionId);
    }

    @Test
    void ac4_closeSession_returns200Closed() {
        String sessionId = startSession();
        var resp = putJson("/api/v1/sessions/" + sessionId + "/close", userHeaders());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("CLOSED");
    }

    @Test
    void ac5_captureIntent_returns201() {
        String sessionId = startSession();
        var resp = postJson("/api/v1/sessions/" + sessionId + "/intents",
                Map.of("intentType", "CREDIT_APPLICATION"), userHeaders());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void ac6_getSession_unknown_returns404() {
        var resp = getJson("/api/v1/sessions/" + UUID.randomUUID(), userHeaders());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac7_listChannels_asAdmin_returns200() {
        var resp = getRaw("/api/v1/channels", adminHeaders());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> getJson(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<String> getRaw(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Object body, HttpHeaders headers) {
        HttpHeaders h = headers != null ? headers : new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<String> postRaw(String path, Object body, HttpHeaders headers) {
        HttpHeaders h = headers != null ? headers : new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private ResponseEntity<Map<String, Object>> putJson(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
    }

    private HttpHeaders userHeaders() {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", "CUSTOMER");
        return h;
    }

    private HttpHeaders adminHeaders() {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", "ADMIN");
        return h;
    }
}
