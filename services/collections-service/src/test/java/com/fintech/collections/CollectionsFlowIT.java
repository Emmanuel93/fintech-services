package com.fintech.collections;

import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.domain.CaseStatus;
import com.fintech.collections.domain.CollectionCase;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration flow — collections consumes credit-portfolio's delinquency signals over Kafka
 * and drives the CollectionCase lifecycle against a real Postgres schema.
 *
 * FLOW-1  credit-account-activated seeds the local snapshot (productType), then
 *         delinquency-status-updated(days>=1) opens a CollectionCase with that productType.
 * FLOW-2  delinquency-status-updated(days=0) on an active case closes it (no separate
 *         DelinquencyCleared event exists — days=0 is the cleared signal).
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.delinquency-status-updated",
        "credit-portfolio.credit-account-activated",
        "credit-portfolio.balance-updated",
        "credit-portfolio.installment-upcoming",
        "payments.payment-applied"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class CollectionsFlowIT {

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

    @Autowired CollectionCaseRepository caseRepository;
    @Autowired AccountBalanceSnapshotRepository snapshotRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void flow1_activationThenDelinquency_opensCaseWithProductType() {
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();

        kafkaTemplate.send("credit-portfolio.credit-account-activated", creditAccountId.toString(),
                activatedPayload(creditAccountId, obligorPartyId, "PERSONAL_LOAN"));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(snapshotRepository.findById(creditAccountId)).isPresent());

        kafkaTemplate.send("credit-portfolio.delinquency-status-updated", creditAccountId.toString(),
                delinquencyPayload(creditAccountId, obligorPartyId, 45));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var found = caseRepository.findActiveByCreditAccountId(creditAccountId);
            assertThat(found).isPresent();
            assertThat(found.get().getProductType()).isEqualTo("PERSONAL_LOAN");
            assertThat(found.get().getCurrentBucket().name()).isEqualTo("B31_60");
        });
    }

    @Test
    void flow2_delinquencyClears_closesActiveCase() {
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();

        // Pre-seed an active case directly, then let the days=0 event close it
        CollectionCase seeded = CollectionCase.open(creditAccountId, obligorPartyId, "PERSONAL_LOAN",
                30, new BigDecimal("4000"), "AUTO_NOTIFY");
        caseRepository.save(seeded);

        kafkaTemplate.send("credit-portfolio.delinquency-status-updated", creditAccountId.toString(),
                delinquencyPayload(creditAccountId, obligorPartyId, 0));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var found = caseRepository.findById(seeded.getCaseId());
            assertThat(found).isPresent();
            assertThat(found.get().getStatus()).isEqualTo(CaseStatus.CLOSED);
        });
    }

    private Map<String, Object> activatedPayload(UUID creditAccountId, UUID obligorPartyId, String productType) {
        Map<String, Object> p = new HashMap<>();
        p.put("eventId", UUID.randomUUID().toString());
        p.put("occurredOn", Instant.now().toString());
        p.put("creditAccountId", creditAccountId.toString());
        p.put("contractId", UUID.randomUUID().toString());
        p.put("obligorPartyId", obligorPartyId.toString());
        p.put("productType", productType);
        p.put("productBehavior", "INSTALLMENT");
        p.put("principalBalance", 50000);
        p.put("riskTier", "BAJO");
        p.put("activatedAt", Instant.now().toString());
        return p;
    }

    private Map<String, Object> delinquencyPayload(UUID creditAccountId, UUID obligorPartyId, int days) {
        Map<String, Object> p = new HashMap<>();
        p.put("creditAccountId", creditAccountId.toString());
        p.put("obligorPartyId", obligorPartyId.toString());
        p.put("contractNumber", "CTR-202607-FLOW0001");
        p.put("daysDelinquent", days);
        p.put("occurredAt", Instant.now().toString());
        return p;
    }
}
