package com.fintech.payments;

import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration flow — payments keeps a local AccountBalanceSnapshot in sync with credit-portfolio
 * over Kafka; the snapshot is the gate for the dual-validation on payment submission.
 *
 * FLOW-1  credit-account-activated → snapshot created (PENDING_ACTIVATION).
 * FLOW-2  balance-updated(ACTIVE, totalDebt) → snapshot reflects ACTIVE + the new debt.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.credit-account-activated",
        "credit-portfolio.balance-updated",
        "credit-portfolio.payment-rejected",
        "payments.payment-applied",
        "payments.payment-reversed"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class PaymentsFlowIT {

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
    }

    @Autowired AccountBalanceSnapshotRepository snapshotRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void flow1_activation_createsSnapshot() {
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();

        Map<String, Object> p = new HashMap<>();
        p.put("eventId", UUID.randomUUID().toString());
        p.put("creditAccountId", creditAccountId.toString());
        p.put("obligorPartyId", obligorPartyId.toString());
        p.put("creditLimit", 50000);
        p.put("activatedAt", java.time.Instant.now().toString());

        kafkaTemplate.send("credit-portfolio.credit-account-activated", creditAccountId.toString(), p);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(snapshotRepository.existsByCreditAccountId(creditAccountId)).isTrue());
    }

    @Test
    void flow2_balanceUpdated_reflectsActiveAndDebt() {
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();

        Map<String, Object> p = new HashMap<>();
        p.put("eventId", UUID.randomUUID().toString());
        p.put("creditAccountId", creditAccountId.toString());
        p.put("obligorPartyId", obligorPartyId.toString());
        p.put("principalBalance", 9000);
        p.put("accruedInterestBalance", 800);
        p.put("penaltyBalance", 200);
        p.put("availableCredit", 41000);
        p.put("totalDebt", 10000);
        p.put("accountStatus", "ACTIVE");
        p.put("balanceVersion", 3);

        kafkaTemplate.send("credit-portfolio.balance-updated", creditAccountId.toString(), p);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var snap = snapshotRepository.findByCreditAccountId(creditAccountId);
            assertThat(snap).isPresent();
            assertThat(snap.get().getAccountStatus()).isEqualTo("ACTIVE");
            assertThat(snap.get().getTotalDebt()).isEqualByComparingTo("10000");
        });
    }
}
