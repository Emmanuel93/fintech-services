package com.fintech.commission;

/**
 * Acceptance criteria — commission-service (HTTP end-to-end, real Postgres + Kafka)
 *
 * AC-1  GET /accounts/{id} no token                     → 401
 * AC-2  GET /accounts/{id} seeded                        → 200 with the accrual
 * AC-3  GET /beneficiaries/{id}/pending                  → 200 with ACCRUED only
 * AC-4  GET /policies                                    → 200 (seeded policies)
 * AC-5  POST /policies as COMMERCIAL                     → 201
 * AC-6  POST /policies wrong role                        → 403
 * AC-7  POST /liquidation-runs as FINANCE                → 200
 */

import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.balance-updated", "credit-portfolio.credit-account-activated",
        "commission.commission-accrued", "commission.commission-reversed", "commission.commission-liquidated"
})
class CommissionAcceptanceTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired CommissionRecordRepository recordRepository;

    private CommissionRecord seedAccrual(UUID creditAccountId, UUID beneficiaryPartyId) {
        return recordRepository.save(CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                creditAccountId, beneficiaryPartyId, "seed-" + UUID.randomUUID(),
                new BigDecimal("1000"), new BigDecimal("0.30")));
    }

    @Test
    void ac1_noToken_returns401() {
        var resp = restTemplate.exchange("/api/v1/commissions/accounts/" + UUID.randomUUID(),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac2_byAccount_returns200WithAccrual() {
        UUID ca = UUID.randomUUID();
        seedAccrual(ca, UUID.randomUUID());
        var resp = getList("/api/v1/commissions/accounts/" + ca, headers("FINANCE"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).anySatisfy(r -> assertThat(((Number) r.get("amount")).doubleValue()).isEqualTo(300.0));
    }

    @Test
    void ac3_pendingByBeneficiary_returns200() {
        UUID beneficiary = UUID.randomUUID();
        seedAccrual(UUID.randomUUID(), beneficiary);
        var resp = getList("/api/v1/commissions/beneficiaries/" + beneficiary + "/pending", headers("FINANCE"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).anySatisfy(r -> assertThat(r.get("status")).isEqualTo("ACCRUED"));
    }

    @Test
    void ac4_listPolicies_returns200WithSeeded() {
        var resp = getList("/api/v1/commissions/policies", headers("FINANCE"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void ac5_createPolicy_asCommercial_returns201() {
        var resp = postJson("/api/v1/commissions/policies",
                Map.of("productType", "SME_LOAN", "commissionType", "COLLECTION_BONUS", "rate", 0.10),
                headers("COMMERCIAL"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void ac6_createPolicy_wrongRole_returns403() {
        var resp = postRaw("/api/v1/commissions/policies",
                Map.of("productType", "SME_LOAN", "commissionType", "COLLECTION_BONUS", "rate", 0.10),
                headers("CUSTOMER"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ac7_liquidationRun_asFinance_returns200() {
        var resp = restTemplate.exchange("/api/v1/commissions/liquidation-runs?period=202607",
                HttpMethod.POST, new HttpEntity<>(headers("FINANCE")),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("batchesCreated");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<List<Map<String, Object>>> getList(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Object body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<String> postRaw(String path, Object body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders headers(String roles) {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", roles);
        return h;
    }
}
