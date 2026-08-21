package com.fintech.payments;

/**
 * Acceptance criteria — payments-service (HTTP end-to-end, real Postgres + Kafka)
 *
 * AC-1  POST /payments with active snapshot        → 200 PENDING
 * AC-2  POST /payments unknown account (no snapshot)→ 422
 * AC-3  POST /payments no token                     → 401
 * AC-4  GET  /accounts/{id}/balance seeded          → 200
 * AC-5  GET  /accounts/{id}/balance unknown         → 404
 * AC-6  GET  /{orderId} unknown                     → 404
 * AC-7  POST /{orderId}/reverse a CONFIRMED order   → 200 REVERSED
 * AC-8  POST /{orderId}/reverse a PENDING order     → 422
 */

import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.payments.application.port.out.PaymentOrderRepository;
import com.fintech.payments.domain.AccountBalanceSnapshot;
import com.fintech.payments.domain.PaymentMethod;
import com.fintech.payments.domain.PaymentOrder;
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
        "credit-portfolio.credit-account-activated",
        "credit-portfolio.balance-updated",
        "credit-portfolio.payment-rejected",
        "payments.payment-applied",
        "payments.payment-reversed"
})
class PaymentsAcceptanceTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired AccountBalanceSnapshotRepository snapshotRepository;
    @Autowired PaymentOrderRepository orderRepository;

    private AccountBalanceSnapshot seedActiveSnapshot(UUID creditAccountId, UUID obligorPartyId, BigDecimal totalDebt) {
        AccountBalanceSnapshot s = AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, new BigDecimal("50000"));
        s.update(totalDebt, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50000"), totalDebt, 1L, "ACTIVE");
        return snapshotRepository.save(s);
    }

    @Test
    void ac3_submitNoToken_returns401() {
        var resp = postJson("/api/v1/payments",
                Map.of("creditAccountId", UUID.randomUUID().toString(),
                        "obligorPartyId", UUID.randomUUID().toString(),
                        "amount", 1000, "paymentMethod", "SPEI", "externalRef", "REF-401"),
                null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac1_submitPayment_returns200Pending() {
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();
        seedActiveSnapshot(creditAccountId, obligorPartyId, new BigDecimal("8000"));

        var resp = postJson("/api/v1/payments",
                Map.of("creditAccountId", creditAccountId.toString(),
                        "obligorPartyId", obligorPartyId.toString(),
                        "amount", 2500, "paymentMethod", "SPEI", "externalRef", "REF-" + UUID.randomUUID()),
                userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("PENDING");
    }

    @Test
    void ac2_submitUnknownAccount_returns422() {
        var resp = postJson("/api/v1/payments",
                Map.of("creditAccountId", UUID.randomUUID().toString(),
                        "obligorPartyId", UUID.randomUUID().toString(),
                        "amount", 1000, "paymentMethod", "SPEI", "externalRef", "REF-" + UUID.randomUUID()),
                userId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void ac4_getBalance_seeded_returns200() {
        UUID creditAccountId = UUID.randomUUID();
        seedActiveSnapshot(creditAccountId, UUID.randomUUID(), new BigDecimal("8000"));

        var resp = getJson("/api/v1/payments/accounts/" + creditAccountId + "/balance", userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("accountStatus")).isEqualTo("ACTIVE");
    }

    @Test
    void ac5_getBalance_unknown_returns404() {
        var resp = getJson("/api/v1/payments/accounts/" + UUID.randomUUID() + "/balance", userId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac6_getOrder_unknown_returns404() {
        var resp = getJson("/api/v1/payments/" + UUID.randomUUID(), userId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac7_reverseConfirmedOrder_returns200Reversed() {
        UUID creditAccountId = UUID.randomUUID();
        PaymentOrder order = PaymentOrder.create(creditAccountId, UUID.randomUUID(),
                new BigDecimal("1500"), PaymentMethod.SPEI, "REF-" + UUID.randomUUID(), 1L);
        order.confirm();
        orderRepository.save(order);

        var resp = postJson("/api/v1/payments/" + order.getPaymentOrderId() + "/reverse",
                Map.of("reason", "SPEI devolution"), userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("REVERSED");
    }

    @Test
    void ac8_reversePendingOrder_returns422() {
        UUID creditAccountId = UUID.randomUUID();
        PaymentOrder order = PaymentOrder.create(creditAccountId, UUID.randomUUID(),
                new BigDecimal("1500"), PaymentMethod.SPEI, "REF-" + UUID.randomUUID(), 1L);
        orderRepository.save(order); // stays PENDING

        var resp = postJson("/api/v1/payments/" + order.getPaymentOrderId() + "/reverse",
                Map.of("reason", "should fail"), userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> getJson(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers != null ? headers : new HttpHeaders()),
                new ParameterizedTypeReference<>() {});
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
        h.set("X-Roles", "CUSTOMER");
        return h;
    }
}
