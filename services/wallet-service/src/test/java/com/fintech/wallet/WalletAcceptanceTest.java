package com.fintech.wallet;

/**
 * Acceptance criteria — wallet-service
 *
 * AC-1  GET /wallet/{creditAccountId} after activation event → 200 with balances
 * AC-2  POST /wallet/{creditAccountId}/payment-instructions (SPEI/PARTIAL) → 201
 * AC-3  POST /wallet/{creditAccountId}/payment-instructions duplicate → 409
 * AC-4  POST /wallet/{creditAccountId}/dispositions → 202
 * AC-5  No token → 401
 * AC-6  Unknown creditAccountId → 404
 */

import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.domain.WalletView;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "wallet.payment-instruction-created",
        "wallet.disposition-requested",
        "wallet.snapshot-updated",
        "credit-portfolio.credit-account-activated",
        "credit-portfolio.balance-updated",
        "credit-portfolio.installment-due"
})
class WalletAcceptanceTest {

    // PostgreSQL is provided via Testcontainers JDBC URL in application-test.properties

    @Autowired TestRestTemplate restTemplate;
    @Autowired WalletViewRepository walletViewRepository;

    @Test
    void ac5_noToken_returns401() {
        var resp = getJson("/api/v1/wallet/" + UUID.randomUUID(), null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac6_unknownAccount_returns404() {
        var resp = getJson("/api/v1/wallet/" + UUID.randomUUID(), userId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac1_activatedAccount_getReturns200WithBalances() {
        var creditAccountId = UUID.randomUUID();
        var view = WalletView.createFromActivation(creditAccountId, UUID.randomUUID(),
                "PERSONAL_LOAN", new BigDecimal("50000"), null);
        walletViewRepository.save(view);

        var resp = getJson("/api/v1/wallet/" + creditAccountId, userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("walletId");
        assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void ac2_createPaymentInstruction_returns201() {
        var creditAccountId = UUID.randomUUID();
        var view = WalletView.createFromActivation(creditAccountId, UUID.randomUUID(),
                "PERSONAL_LOAN", new BigDecimal("50000"), null);
        view.applyStatementGenerated(new BigDecimal("2500"), java.time.LocalDate.of(2026, 8, 15));
        walletViewRepository.save(view);

        var resp = postJson("/api/v1/wallet/" + creditAccountId + "/payment-instructions",
                Map.of("paymentMethod", "SPEI", "amount", 5000, "paymentType", "PARTIAL"),
                userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsKey("instructionId");
        assertThat(resp.getBody().get("status")).isEqualTo("PENDING");
    }

    @Test
    void ac3_duplicateInstruction_returns409() {
        var creditAccountId = UUID.randomUUID();
        var view = WalletView.createFromActivation(creditAccountId, UUID.randomUUID(),
                "PERSONAL_LOAN", new BigDecimal("50000"), null);
        view.applyStatementGenerated(new BigDecimal("2500"), java.time.LocalDate.of(2026, 8, 15));
        walletViewRepository.save(view);
        var headers = userId();

        // First instruction
        postJson("/api/v1/wallet/" + creditAccountId + "/payment-instructions",
                Map.of("paymentMethod", "SPEI", "amount", 1000, "paymentType", "PARTIAL"), headers);

        // Duplicate
        var resp = postJson("/api/v1/wallet/" + creditAccountId + "/payment-instructions",
                Map.of("paymentMethod", "SPEI", "amount", 2000, "paymentType", "PARTIAL"), headers);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void ac4_requestDisposition_returns202() {
        var creditAccountId = UUID.randomUUID();
        var view = WalletView.createFromActivation(creditAccountId, UUID.randomUUID(),
                "REVOLVING_CREDIT", new BigDecimal("10000"), new BigDecimal("8000"));
        walletViewRepository.save(view);

        var resp = postJson("/api/v1/wallet/" + creditAccountId + "/dispositions",
                Map.of("amount", 3000, "dispositionType", "CASH_ADVANCE"),
                userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
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

    private HttpHeaders userId() {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        return h;
    }
}
