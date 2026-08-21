package com.fintech.commission;

import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.port.out.CreditPromoterAssignmentRepository;
import com.fintech.commission.domain.CommissionRecordStatus;
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
 * Integration flow — the full B2B2C distributor model against a real Postgres and a real
 * (embedded) broker: activation attributes the credit to the distributor (promoterCode = its Party
 * UUID, prerequisite propagated end-to-end from channels), then a payment that collects interest
 * accrues the commission — never on colocation, always against the payment (CM-01).
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.balance-updated", "credit-portfolio.credit-account-activated",
        "commission.commission-accrued", "commission.commission-reversed", "commission.commission-liquidated"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class CommissionFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired CreditPromoterAssignmentRepository assignmentRepository;
    @Autowired CommissionRecordRepository recordRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void flow_activationThenPayment_accruesDistributorCommission() {
        UUID creditAccountId = UUID.randomUUID();
        UUID distributorPartyId = UUID.randomUUID();

        // 1. Activation carries the distributor attribution (promoterCode = its Party UUID)
        Map<String, Object> activated = new HashMap<>();
        activated.put("creditAccountId", creditAccountId.toString());
        activated.put("productType", "DISTRIBUTOR_LINE");
        activated.put("promoterCode", distributorPartyId.toString());
        kafkaTemplate.send("credit-portfolio.credit-account-activated", creditAccountId.toString(), activated);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var assignment = assignmentRepository.findById(creditAccountId);
            assertThat(assignment).isPresent();
            assertThat(assignment.get().getBeneficiaryPartyId()).isEqualTo(distributorPartyId);
        });

        // 2. A payment that collects interest (accruedInterestBalance drops 1000 -> 400 = 600 collected)
        Map<String, Object> firstBalance = new HashMap<>();
        firstBalance.put("eventId", "evt-init");
        firstBalance.put("creditAccountId", creditAccountId.toString());
        firstBalance.put("accruedInterestBalance", 1000);
        firstBalance.put("triggerEvent", "CHARGE_ORDINARY_INTEREST");
        firstBalance.put("balanceVersion", 1);
        kafkaTemplate.send("credit-portfolio.balance-updated", creditAccountId.toString(), firstBalance);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(recordRepository.findByCreditAccountId(creditAccountId)).isEmpty()); // charge alone never accrues (CM-01)

        Map<String, Object> payment = new HashMap<>();
        payment.put("eventId", "evt-payment-1");
        payment.put("creditAccountId", creditAccountId.toString());
        payment.put("accruedInterestBalance", 400);
        payment.put("triggerEvent", "PAYMENT_APPLIED");
        payment.put("balanceVersion", 2);
        kafkaTemplate.send("credit-portfolio.balance-updated", creditAccountId.toString(), payment);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var records = recordRepository.findByCreditAccountId(creditAccountId);
            assertThat(records).hasSize(1);
            assertThat(records.get(0).getBasis()).isEqualByComparingTo("600");
            assertThat(records.get(0).getBeneficiaryPartyId()).isEqualTo(distributorPartyId);
            assertThat(records.get(0).getStatus()).isEqualTo(CommissionRecordStatus.ACCRUED);
        });
    }
}
