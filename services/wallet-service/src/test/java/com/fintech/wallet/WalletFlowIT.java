package com.fintech.wallet;

import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.application.service.WalletProjectionService;
import com.fintech.wallet.domain.WalletView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration flow — wallet projects credit-portfolio.credit-account-activated into a local
 * WalletView (WV-01). Verifies the messaging → projection path against a real Postgres and a real
 * (embedded) broker. Complements WalletAcceptanceTest (HTTP contract).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.credit-account-activated", "credit-portfolio.balance-updated",
        "credit-portfolio.installment-due", "credit-portfolio.disposition-completed",
        "wallet.payment-instruction-created", "wallet.disposition-requested",
        "wallet.snapshot-updated", "wallet.withdrawal-completed"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class WalletFlowIT {

    @Autowired WalletViewRepository walletViewRepository;
    @Autowired WalletProjectionService projections;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    /** Every status credit-portfolio's accounts can have (its CreditAccountStatus), and the stub's own UNKNOWN. */
    private static final List<String> PORTFOLIO_STATUSES = List.of("PENDING_ACTIVATION", "ACTIVE", "SUSPENDED",
            "RESTRUCTURED", "SETTLED", "WRITTEN_OFF", "CLOSED", "CANCELLED", "UNKNOWN");

    @Test
    @DisplayName("D63 · un crédito cancelado porque su desembolso no se hizo queda en la billetera CANCELLED y en cero")
    void aCancelledCreditIsProjected() {
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();
        projections.onCreditAccountActivated(creditAccountId, obligorPartyId, "PERSONAL_LOAN",
                new BigDecimal("20000.02"), null);

        Map<String, Object> p = new HashMap<>();
        p.put("eventId", UUID.randomUUID().toString());
        p.put("occurredOn", Instant.now().toString());
        p.put("creditAccountId", creditAccountId.toString());
        p.put("obligorPartyId", obligorPartyId.toString());
        p.put("principalBalance", 0);
        p.put("accruedInterestBalance", 0);
        p.put("penaltyBalance", 0);
        p.put("totalDebt", 0);
        p.put("triggerEvent", "DISPOSITION_FAILED");
        p.put("accountStatus", "CANCELLED");
        p.put("balanceVersion", 5);
        kafkaTemplate.send("credit-portfolio.balance-updated", creditAccountId.toString(), p);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            WalletView view = walletViewRepository.findByCreditAccountId(creditAccountId).orElseThrow();
            assertThat(view.getStatus()).isEqualTo("CANCELLED");
            assertThat(view.getTotalDebt()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("la billetera acepta cada estado que publica cartera; rendimiento: 450 saldos en menos de 15 s")
    void everyPortfolioStatusIsTaken() {
        long started = System.nanoTime();
        int n = 0;
        for (int round = 0; round < 50; round++) {
            for (String status : PORTFOLIO_STATUSES) {
                UUID creditAccountId = UUID.randomUUID();
                projections.onBalanceUpdated(creditAccountId, UUID.randomUUID(), new BigDecimal("1000"),
                        BigDecimal.ZERO, BigDecimal.ZERO, null, new BigDecimal("1000"), status, 1);
                assertThat(walletViewRepository.findByCreditAccountId(creditAccountId).orElseThrow().getStatus())
                        .isEqualTo(status);
                n++;
            }
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertThat(ms).as("%d saldos en %d ms", n, ms).isLessThan(15_000);
    }

    @Test
    void activation_projectsWalletView() {
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();

        Map<String, Object> p = new HashMap<>();
        p.put("eventId", UUID.randomUUID().toString());
        p.put("occurredOn", Instant.now().toString());
        p.put("creditAccountId", creditAccountId.toString());
        p.put("contractId", UUID.randomUUID().toString());
        p.put("obligorPartyId", obligorPartyId.toString());
        p.put("productType", "PERSONAL_LOAN");
        p.put("productBehavior", "INSTALLMENT");
        p.put("principalBalance", 50000);
        p.put("creditLimit", null);

        kafkaTemplate.send("credit-portfolio.credit-account-activated", creditAccountId.toString(), p);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var view = walletViewRepository.findByCreditAccountId(creditAccountId);
            assertThat(view).isPresent();
            assertThat(view.get().getObligorPartyId()).isEqualTo(obligorPartyId);
        });
    }
}
