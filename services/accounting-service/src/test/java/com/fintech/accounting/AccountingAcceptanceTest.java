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

    /**
     * La misma zona que `fintech.accounting.zone`. Contabilidad deriva el período en hora de
     * México; calcularlo aquí con el reloj de la máquina hace que en la ventana entre un cambio de
     * mes y el otro la prueba pida las partidas de un mes que contabilidad archivó en el siguiente.
     */
    private static final java.time.ZoneId ZONA_CONTABLE = java.time.ZoneId.of("America/Mexico_City");

    @Autowired TestRestTemplate restTemplate;
    @Autowired JournalEntryRepository journalRepository;

    /**
     * Siembra en el período <b>vigente</b>, no en uno a fuego.
     *
     * <p>Decía {@code "202608"}. Las consultas de abajo piden el período actual, así que la prueba
     * sólo funcionaba durante agosto de 2026: el 1 de septiembre iba a romperse para todo el mundo,
     * sin que nadie tocara nada. Un literal de fecha en un fixture es una bomba con temporizador.
     */
    private JournalEntry seedEntry(UUID creditAccountId, UUID partyId) {
        return journalRepository.save(JournalEntry.post(
                "seed-" + UUID.randomUUID(), "CHARGE_ORDINARY_INTEREST", creditAccountId, partyId,
                "1203", "4101", new BigDecimal("100"), "MXN", "Interés seed",
                null, "S_SEED", currentPeriod(), java.time.Instant.now(), (short) 1));
    }

    /** El período tal como lo deriva contabilidad: en su zona, no en la de la máquina. */
    private static String currentPeriod() {
        return YearMonth.now(ZONA_CONTABLE).toString().replace("-", "");
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
        String period = currentPeriod();
        var resp = getList("/api/v1/accounting/trial-balance?period=" + period);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    /**
     * The trial balance carries each account's opening and closing balance, not only the period's movement: a month
     * that collected more than it lent showed the loan portfolio "negative", while its balance was the portfolio.
     */
    @Test
    void ac4b_trialBalance_carriesOpeningAndClosingBalances() {
        String unit = "S_TB_" + UUID.randomUUID().toString().substring(0, 8);
        UUID ca = UUID.randomUUID(), party = UUID.randomUUID();
        // January: 10,000 lent. February: 2,500 collected, 1,000 lent again.
        journalRepository.save(JournalEntry.post("tb-1-" + unit, "DISPOSITION_AT_ORIGINATION", ca, party, "1201", "1101",
                new BigDecimal("10000"), "MXN", "Capital colocado", null, unit, "190001", java.time.Instant.now(), (short) 1));
        journalRepository.save(JournalEntry.post("tb-2-" + unit, "PAYMENT_APPLIED", ca, party, "1101", "1201",
                new BigDecimal("2500"), "MXN", "Pago a capital", null, unit, "190002", java.time.Instant.now(), (short) 1));
        journalRepository.save(JournalEntry.post("tb-3-" + unit, "DISPOSITION_SELF_USE", ca, party, "1201", "1101",
                new BigDecimal("1000"), "MXN", "Capital colocado", null, unit, "190002", java.time.Instant.now(), (short) 1));

        var resp = getList("/api/v1/accounting/trial-balance?period=190002&unitCodes=" + unit);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> portfolio = resp.getBody().stream()
                .filter(r -> "1201".equals(r.get("accountCode"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(String.valueOf(portfolio.get("openingBalance")))).isEqualByComparingTo("10000");
        assertThat(new BigDecimal(String.valueOf(portfolio.get("totalDebit")))).isEqualByComparingTo("1000");
        assertThat(new BigDecimal(String.valueOf(portfolio.get("totalCredit")))).isEqualByComparingTo("2500");
        assertThat(new BigDecimal(String.valueOf(portfolio.get("balance")))).isEqualByComparingTo("-1500");
        assertThat(new BigDecimal(String.valueOf(portfolio.get("closingBalance")))).isEqualByComparingTo("8500");
    }

    @Test
    void ac5_billingRun_byFinance_returns200() {
        String period = currentPeriod();
        HttpHeaders finanzas = userHeaders();
        finanzas.set("X-Roles", "FINANCE");
        var resp = restTemplate.exchange("/api/v1/accounting/billing-runs?period=" + period,
                HttpMethod.POST, new HttpEntity<>(finanzas),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("invoicesRequested");
    }

    @Test
    void ac6_closingOrBillingAPeriod_withoutFinanceRole_isForbidden() {
        String period = currentPeriod();
        // An accountant reads the ledger; closing or billing the month publishes the numbers and is finance's call.
        for (String path : List.of("/api/v1/accounting/billing-runs?period=" + period,
                                   "/api/v1/accounting/periods/" + period + "/close",
                                   "/api/v1/accounting/periods/" + period + "/reopen")) {
            var resp = restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(userHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
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
