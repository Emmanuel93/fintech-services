package com.fintech.notifications;

/**
 * Acceptance criteria — notifications-service (HTTP end-to-end, real Postgres + Kafka)
 *
 * AC-1  GET /history/{partyId} sin token             → 401
 * AC-2  GET /history/{partyId} con registros          → 200
 * AC-3  GET /preferences/{partyId} sin registrar       → 404
 * AC-4  PUT /preferences/{partyId}                      → 200, luego GET la refleja
 * AC-5  GET /policies                                    → 200 (6 policies seed de v1)
 * AC-6  POST /policies como MARKETING                     → 201
 * AC-7  POST /policies rol incorrecto                      → 403
 */

import com.fintech.notifications.application.port.out.NotificationRecordRepository;
import com.fintech.notifications.domain.EventType;
import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.NotificationRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "origination.prospect-created", "origination.offer-presented",
        "credit-portfolio.credit-account-activated", "credit-portfolio.disposition-completed",
        "collections.pre-due-reminder-triggered", "payments.payment-applied",
        "credit-portfolio.balance-updated",
        "notifications.notification-sent", "notifications.notification-failed"
})
class NotificationAcceptanceTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired NotificationRecordRepository recordRepository;

    @Test
    void ac1_noToken_returns401() {
        var resp = restTemplate.exchange("/api/v1/notifications/history/" + UUID.randomUUID(),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac2_history_returns200WithSeededRecord() {
        UUID recipientId = UUID.randomUUID();
        recordRepository.save(NotificationRecord.sent(
                "seed-" + UUID.randomUUID(), recipientId, EventType.WELCOME_ACTIVATED, NotificationChannel.PUSH_NOTIFICATION));

        var resp = getList("/api/v1/notifications/history/" + recipientId, headers("CUSTOMER"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).anySatisfy(r -> assertThat(r.get("eventType")).isEqualTo("WELCOME_ACTIVATED"));
    }

    @Test
    void ac3_preferencesNotRegistered_returns404() {
        var resp = restTemplate.exchange("/api/v1/notifications/preferences/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(headers("CUSTOMER")), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac4_updatePreferences_thenGetReflectsIt() {
        UUID partyId = UUID.randomUUID();
        var putResp = restTemplate.exchange("/api/v1/notifications/preferences/" + partyId,
                HttpMethod.PUT, new HttpEntity<>(Map.of("pushToken", "tok-1", "whatsappNumber", "5511112222"),
                        jsonHeaders("CUSTOMER")),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var getResp = restTemplate.exchange("/api/v1/notifications/preferences/" + partyId,
                HttpMethod.GET, new HttpEntity<>(headers("CUSTOMER")),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(getResp.getBody().get("pushToken")).isEqualTo("tok-1");
    }

    @Test
    void ac5_listPolicies_returns200WithSeeded() {
        var resp = getList("/api/v1/notifications/policies", headers("MARKETING"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(6); // 6 policies v1 (seed 009)
    }

    @Test
    void ac6_createPolicy_asMarketing_returns201() {
        var resp = restTemplate.exchange("/api/v1/notifications/policies", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "eventType", "PAYMENT_REMINDER",
                        "valueTier", "ALTO",
                        "channelStrategy", "SEQUENTIAL_FALLBACK",
                        "primaryChannel", "WHATSAPP",
                        "fallbackChannels", List.of("EMAIL")
                ), jsonHeaders("MARKETING")),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void ac7_createPolicy_wrongRole_returns403() {
        var resp = restTemplate.exchange("/api/v1/notifications/policies", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "eventType", "PAYMENT_REMINDER",
                        "valueTier", "ALTO",
                        "channelStrategy", "SEQUENTIAL_FALLBACK",
                        "primaryChannel", "WHATSAPP",
                        "fallbackChannels", List.of()
                ), jsonHeaders("CUSTOMER")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<List<Map<String, Object>>> getList(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
    }

    private HttpHeaders headers(String roles) {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", roles);
        return h;
    }

    private HttpHeaders jsonHeaders(String roles) {
        var h = headers(roles);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
