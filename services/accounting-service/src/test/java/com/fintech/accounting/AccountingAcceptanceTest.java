package com.fintech.accounting;

/**
 * Acceptance criteria — accounting-service (HTTP end-to-end, real Postgres + Kafka)
 *
 * AC-1  GET journal no token                     → 401
 * AC-2  GET /accounts/{id}/journal (seeded)      → 200 with entry
 * AC-3  GET /parties/{id}/journal                → 200
 * AC-4  GET /trial-balance?period=               → 200 (balanced mayor)
 * AC-5  POST /billing-runs?period=               → 200
 */

import com.fintech.accounting.application.port.out.JournalEntryRepository;
import com.fintech.accounting.domain.JournalEntry;
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
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.balance-updated", "risk.assessment-updated",
        "collections.recovery-payment-applied", "wallet.withdrawal-completed",
        "accounting.journal-entry-created", "accounting.invoice-requested", "accounting.reconciliation-alert"
})
class AccountingAcceptanceTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired JournalEntryRepository journalRepository;

    private JournalEntry seedEntry(UUID creditAccountId, UUID partyId) {
        return journalRepository.save(JournalEntry.post(
                "seed-" + UUID.randomUUID(), "CHARGE_ORDINARY_INTEREST", creditAccountId, partyId,
                "1203", "4101", new BigDecimal("100"), "MXN", "Interés seed",
                null, "S_SEED", "202608", java.time.Instant.now(), (short) 1));
    }

    @Test
    void ac1_noToken_returns401() {
        var resp = restTemplate.exchange("/api/v1/accounting/accounts/" + UUID.randomUUID() + "/journal",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac2_journalByAccount_returns200WithEntry() {
        UUID ca = UUID.randomUUID();
        seedEntry(ca, UUID.randomUUID());
        var resp = getList("/api/v1/accounting/accounts/" + ca + "/journal");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).anySatisfy(e -> assertThat(e.get("debitAccount")).isEqualTo("1203"));
    }

    @Test
    void ac3_journalByParty_returns200() {
        UUID party = UUID.randomUUID();
        seedEntry(UUID.randomUUID(), party);
        var resp = getList("/api/v1/accounting/parties/" + party + "/journal");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void ac4_trialBalance_returns200() {
        seedEntry(UUID.randomUUID(), UUID.randomUUID());
        String period = YearMonth.now().toString().replace("-", "");
        var resp = getList("/api/v1/accounting/trial-balance?period=" + period);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void ac5_billingRun_returns200() {
        String period = YearMonth.now().toString().replace("-", "");
        var resp = restTemplate.exchange("/api/v1/accounting/billing-runs?period=" + period,
                HttpMethod.POST, new HttpEntity<>(userHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("invoicesRequested");
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(userHeaders()),
                new ParameterizedTypeReference<>() {});
    }

    private HttpHeaders userHeaders() {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", "ACCOUNTANT");
        return h;
    }
}
