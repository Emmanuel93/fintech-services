package com.fintech.charges;

import com.fintech.charges.application.port.out.AccountBalanceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration flow — charges reacts to credit-portfolio.credit-account-activated by creating its
 * local accrual schedule + balance snapshot. Verifies the messaging → persistence path against a
 * real Postgres and a real (embedded) broker. Complements ChargesAcceptanceTest (HTTP contract).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.credit-account-activated", "credit-portfolio.balance-updated",
        "charges.charge-applied", "charges.charge-reversed"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class ChargesFlowIT {

    @Autowired AccountBalanceSnapshotRepository snapshotRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void activation_createsLocalSnapshot() {
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();

        Map<String, Object> p = new HashMap<>();
        p.put("eventId", UUID.randomUUID().toString());
        p.put("creditAccountId", creditAccountId.toString());
        p.put("contractId", UUID.randomUUID().toString());
        p.put("obligorPartyId", obligorPartyId.toString());
        p.put("productType", "PERSONAL_LOAN");
        p.put("productBehavior", "INSTALLMENT");
        p.put("nominalRate", 24.0);
        p.put("moratoriumRate", 36.0);
        p.put("openingFeeRate", 3.0);
        p.put("principalBalance", 50000);
        p.put("creditLimit", null);
        p.put("riskTier", "BAJO");

        kafkaTemplate.send("credit-portfolio.credit-account-activated", creditAccountId.toString(), p);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(snapshotRepository.existsByCreditAccountId(creditAccountId)).isTrue());
    }
}
