package com.fintech.creditportfolio;

/**
 * Acceptance criteria — credit-portfolio-service (HTTP end-to-end, real Postgres + Kafka).
 * Complements the event-flow ITs with the read-side HTTP contract.
 *
 * AC-1  GET /portfolio/accounts/{id} no token        → 401
 * AC-2  GET /portfolio/accounts/{id} seeded          → 200 with balances
 * AC-3  GET /portfolio/accounts/{unknown}            → 404
 * AC-4  GET /portfolio/accounts?partyId=             → 200 list
 * AC-5  GET /portfolio/accounts/{id}/dispositions    → 200
 * AC-6  GET /portfolio/accounts/{id}/amortization-schedule → 200
 */

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "origination.credit-product-creation-requested",
        "product-catalog.product-activated", "product-catalog.product-retired",
        "charges.charge-applied", "charges.charge-reversed",
        "payments.payment-applied", "payments.payment-returned",
        "wallet.disposition-requested", "collections.write-off-executed", "collections.agreement-executed",
        "credit-portfolio.credit-account-activated", "credit-portfolio.balance-updated"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class CreditPortfolioAcceptanceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("fintech.credit-portfolio.jwt-secret",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo");
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired CreditAccountRepository accountRepository;

    private CreditAccount seedActiveAccount(UUID obligorPartyId) {
        CreditAccount account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-" + UUID.randomUUID().toString().substring(0, 8), obligorPartyId,
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", new BigDecimal("3.0"), "032180000118359719", "BAJO", null, null);
        account.activate(new BigDecimal("50000"));
        return accountRepository.save(account);
    }

    @Test
    void ac1_getAccount_noToken_returns401() {
        var resp = restTemplate.exchange("/api/v1/portfolio/accounts/" + UUID.randomUUID(),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac2_getAccount_seeded_returns200() {
        CreditAccount account = seedActiveAccount(UUID.randomUUID());
        var resp = getMap("/api/v1/portfolio/accounts/" + account.getCreditAccountId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("productType")).isEqualTo("PERSONAL_LOAN");
        assertThat(resp.getBody().get("principalBalance")).isNotNull();
    }

    @Test
    void ac3_getAccount_unknown_returns404() {
        var resp = getMap("/api/v1/portfolio/accounts/" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac4_listByParty_returns200() {
        UUID partyId = UUID.randomUUID();
        seedActiveAccount(partyId);
        var resp = getList("/api/v1/portfolio/accounts?partyId=" + partyId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void ac5_getDispositions_returns200() {
        CreditAccount account = seedActiveAccount(UUID.randomUUID());
        var resp = getList("/api/v1/portfolio/accounts/" + account.getCreditAccountId() + "/dispositions");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac6_getAmortizationSchedule_returns200() {
        CreditAccount account = seedActiveAccount(UUID.randomUUID());
        var resp = getList("/api/v1/portfolio/accounts/" + account.getCreditAccountId() + "/amortization-schedule");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> getMap(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(userHeaders()),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(userHeaders()),
                new ParameterizedTypeReference<>() {});
    }

    private HttpHeaders userHeaders() {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", "CUSTOMER");
        return h;
    }
}
