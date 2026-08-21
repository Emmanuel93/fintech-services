package com.fintech.risk;

import com.fintech.risk.application.port.out.RiskProfileRepository;
import com.fintech.risk.application.service.RiskAssessmentService;
import com.fintech.risk.domain.Ifrs9Stage;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration flow — risk consumes credit-portfolio's activation/balance/delinquency signals into a
 * local RiskProfile, then the nightly assessment applies the seeded ProvisionPolicy and publishes
 * risk.assessment-updated. Exercised against a real Postgres (with the Liquibase seed) and a real
 * (embedded) broker.
 *
 * FLOW-1  credit-account-activated → RiskProfile created.
 * FLOW-2  activated + balance-updated(ead) + delinquency(45) → reassessment computes provision =
 *         ead × rate(B31_60) at STAGE_2 and emits risk.assessment-updated.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.credit-account-activated", "credit-portfolio.balance-updated",
        "credit-portfolio.delinquency-status-updated", "collections.agreement-executed",
        "risk.assessment-updated"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class RiskFlowIT {

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

    @Autowired RiskProfileRepository profileRepository;
    @Autowired RiskAssessmentService riskAssessmentService;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired EmbeddedKafkaBroker embeddedKafka;

    @Test
    void flow1_activation_createsProfile() {
        UUID creditAccountId = UUID.randomUUID();
        kafkaTemplate.send("credit-portfolio.credit-account-activated", creditAccountId.toString(),
                activatedPayload(creditAccountId, UUID.randomUUID(), "PERSONAL_LOAN"));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(profileRepository.findByCreditAccountId(creditAccountId)).isPresent());
    }

    @Test
    void flow2_assessment_computesProvisionAndPublishes() {
        UUID creditAccountId = UUID.randomUUID();
        UUID partyId = UUID.randomUUID();

        kafkaTemplate.send("credit-portfolio.credit-account-activated", creditAccountId.toString(),
                activatedPayload(creditAccountId, partyId, "PERSONAL_LOAN"));
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(profileRepository.findByCreditAccountId(creditAccountId)).isPresent());

        // Sequence the two events (distinct topics → distinct concurrent listeners) so the second
        // load-mutate-save doesn't clobber the first — the read-model has no optimistic lock, same
        // as the other services' local projections.
        Map<String, Object> balance = new HashMap<>();
        balance.put("creditAccountId", creditAccountId.toString());
        balance.put("obligorPartyId", partyId.toString());
        balance.put("totalDebt", 1000);
        balance.put("accountStatus", "ACTIVE");
        kafkaTemplate.send("credit-portfolio.balance-updated", creditAccountId.toString(), balance);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(profileRepository.findByCreditAccountId(creditAccountId).orElseThrow().getEad())
                        .isEqualByComparingTo("1000"));

        Map<String, Object> delinquency = new HashMap<>();
        delinquency.put("creditAccountId", creditAccountId.toString());
        delinquency.put("daysDelinquent", 45);
        kafkaTemplate.send("credit-portfolio.delinquency-status-updated", creditAccountId.toString(), delinquency);
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(profileRepository.findByCreditAccountId(creditAccountId).orElseThrow().getDaysDelinquent())
                        .isEqualTo(45));

        // Subscribe before running the nightly assessment so we capture the emitted event
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of("risk.assessment-updated"));

            riskAssessmentService.reassessAll();

            var p = profileRepository.findByCreditAccountId(creditAccountId).orElseThrow();
            assertThat(p.getIfrs9Stage()).isEqualTo(Ifrs9Stage.STAGE_2);
            assertThat(p.getProvisionAmount()).isEqualByComparingTo("150.00000"); // 1000 * 0.15 (seeded B31_60)

            boolean found = false;
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && !found) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (creditAccountId.toString().equals(r.key())) { found = true; break; }
                }
            }
            assertThat(found).as("risk.assessment-updated emitted for the account").isTrue();
        }
    }

    private KafkaConsumer<String, String> newConsumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-risk-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private Map<String, Object> activatedPayload(UUID creditAccountId, UUID partyId, String productType) {
        Map<String, Object> p = new HashMap<>();
        p.put("creditAccountId", creditAccountId.toString());
        p.put("obligorPartyId", partyId.toString());
        p.put("productType", productType);
        return p;
    }
}
