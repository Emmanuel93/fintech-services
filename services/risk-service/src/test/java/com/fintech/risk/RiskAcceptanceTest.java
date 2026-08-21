package com.fintech.risk;

/**
 * Acceptance criteria — risk-service (HTTP end-to-end, real Postgres + Kafka)
 *
 * AC-1  GET /risk/accounts/{id} no token          → 401
 * AC-2  GET /risk/accounts/{id} seeded            → 200 STAGE_1
 * AC-3  GET /risk/accounts/{unknown}              → 404
 * AC-4  GET /risk/accounts?partyId=               → 200 list
 * AC-5  GET /risk/provisions/summary              → 200
 * AC-6  GET /risk/provision-policies              → 200 (seeded policies)
 * AC-7  GET /risk/provision-policies/PERSONAL_LOAN→ 200 with rate matrix
 * AC-8  POST /risk/provision-policies as analyst  → 201
 * AC-9  POST /risk/provision-policies wrong role  → 403
 */

import com.fintech.risk.application.port.out.RiskProfileRepository;
import com.fintech.risk.domain.RiskProfile;
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
        "credit-portfolio.credit-account-activated", "credit-portfolio.balance-updated",
        "credit-portfolio.delinquency-status-updated", "collections.agreement-executed",
        "risk.assessment-updated"
})
class RiskAcceptanceTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired RiskProfileRepository profileRepository;

    private RiskProfile seedProfile(UUID partyId) {
        return profileRepository.save(
                RiskProfile.create(UUID.randomUUID(), partyId, "PERSONAL_LOAN"));
    }

    private static Map<String, Object> fullRates() {
        return Map.of("CURRENT", 0.01, "B1_30", 0.03, "B31_60", 0.15, "B61_90", 0.30,
                "B91_120", 0.60, "B121_180", 0.80, "B181_PLUS", 1.00);
    }

    @Test
    void ac1_noToken_returns401() {
        var resp = restTemplate.exchange("/api/v1/risk/accounts/" + UUID.randomUUID(),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac2_getAccount_seeded_returns200() {
        RiskProfile p = seedProfile(UUID.randomUUID());
        var resp = getMap("/api/v1/risk/accounts/" + p.getCreditAccountId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("ifrs9Stage")).isEqualTo("STAGE_1");
        assertThat(resp.getBody().get("bucket")).isEqualTo("CURRENT");
    }

    @Test
    void ac3_getAccount_unknown_returns404() {
        var resp = getMap("/api/v1/risk/accounts/" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac4_list_paginated_returns200() {
        UUID partyId = UUID.randomUUID();
        seedProfile(partyId);
        // /accounts ahora es una bandeja paginada (partyId opcional) → Page, no List.
        var resp = getMap("/api/v1/risk/accounts?partyId=" + partyId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) resp.getBody().get("content")).isNotEmpty();
    }

    @Test
    void ac4b_batchByAccountIds_returns200() {
        RiskProfile p = seedProfile(UUID.randomUUID());
        var resp = getList("/api/v1/risk/accounts/batch?ids=" + p.getCreditAccountId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void ac5_provisionSummary_returns200() {
        var resp = getMap("/api/v1/risk/provisions/summary");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("totalProvision");
    }

    @Test
    void ac6_listPolicies_returns200WithSeeded() {
        var resp = getList("/api/v1/risk/provision-policies");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void ac7_getPolicyByProduct_returns200WithRates() {
        var resp = getMap("/api/v1/risk/provision-policies/PERSONAL_LOAN");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("productType")).isEqualTo("PERSONAL_LOAN");
        assertThat(resp.getBody()).containsKey("rates");
    }

    @Test
    void ac8_createPolicy_asAnalyst_returns201() {
        var resp = postJson("/api/v1/risk/provision-policies",
                Map.of("productType", "AUTO_LOAN", "rates", fullRates()),
                headers("RISK_ANALYST"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
        assertThat(resp.getBody().get("version")).isEqualTo(1);
    }

    @Test
    void ac9_createPolicy_wrongRole_returns403() {
        var resp = postRaw("/api/v1/risk/provision-policies",
                Map.of("productType", "MICRO_LOAN", "rates", fullRates()),
                headers("CUSTOMER"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> getMap(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers("RISK_VIEWER")),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers("RISK_VIEWER")),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Object body, HttpHeaders h) {
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<String> postRaw(String path, Object body, HttpHeaders h) {
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    private HttpHeaders headers(String roles) {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", roles);
        return h;
    }
}
