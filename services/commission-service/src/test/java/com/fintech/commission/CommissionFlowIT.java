package com.fintech.commission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.commission.application.port.out.BeneficiaryProfileRepository;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.port.out.CreditPromoterAssignmentRepository;
import com.fintech.commission.application.port.out.LiquidationBatchRepository;
import com.fintech.commission.application.service.BeneficiaryProfileService;
import com.fintech.commission.application.service.CommissionSelfCheckService;
import com.fintech.commission.application.service.LiquidationService;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import com.fintech.commission.domain.LiquidationBatch;
import com.fintech.commission.domain.LiquidationBatchStatus;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
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
 * The distributor's commission end to end against a real Postgres and an embedded broker: activation attributes the
 * loan to the distributor, party publishes its fiscal profile (a natural person), treasury registers its payout CLABE;
 * a payment that collected interest accrues the commission and its IVA (CM-01); the run opens the lot with the ISR and
 * IVA withheld and requests the SPEI of the net; the rail rejects it and the lot waits; the next run requests it again
 * and the bank settles it; the payment comes back and its commission is clawed back from the next lot.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.balance-updated", "credit-portfolio.credit-account-activated", "party.fiscal-profile-updated",
        "commission.commission-accrued", "commission.commission-reversed", "commission.lot-payout-requested",
        "commission.lot-payout-failed", "disbursement.completed", "disbursement.failed", "disbursement.returned"
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
    @Autowired LiquidationBatchRepository lots;
    @Autowired BeneficiaryProfileRepository profiles;
    @Autowired BeneficiaryProfileService beneficiaryProfiles;
    @Autowired LiquidationService liquidation;
    @Autowired CommissionSelfCheckService selfChecks;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired EmbeddedKafkaBroker broker;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theDistributorsCommissionFromThePaymentToTheSpeiAndBack() throws Exception {
        UUID loan = UUID.randomUUID();
        UUID distributor = UUID.randomUUID();
        Consumer<String, String> payouts = consumerOf("commission.lot-payout-requested");

        // 1. Attribution, the fiscal profile (13-character RFC: a natural person) and the payout account.
        kafkaTemplate.send("credit-portfolio.credit-account-activated", loan.toString(),
                Map.of("creditAccountId", loan.toString(), "productType", "DISTRIBUTOR_LINE",
                        "promoterCode", distributor.toString()));
        kafkaTemplate.send("party.fiscal-profile-updated", distributor.toString(),
                Map.of("partyId", distributor.toString(), "partyType", "INDIVIDUAL", "rfc", "PEGJ800101AB1",
                        "taxName", "JUANA PEREZ GOMEZ", "taxRegime", "612"));
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(assignmentRepository.findById(loan)).isPresent();
            assertThat(profiles.findById(distributor)).hasValueSatisfying(p -> assertThat(p.getRfc()).isEqualTo("PEGJ800101AB1"));
        });
        beneficiaryProfiles.registerPayoutAccount(distributor, "646180157000000004", "Juana Pérez Gómez");

        // 2. A payment of 10 July that paid 3,000 of interest: the distributor's 18 % (migration 012) = 540 of commission,
        //    86.40 of IVA. (At 18 %, 2,000 of interest earn 360, under the 500 a lot needs: they would carry over.)
        kafkaTemplate.send("credit-portfolio.balance-updated", loan.toString(),
                balance("evt-pago-1", loan, "PAYMENT_APPLIED", 2, "-3000.00", "pago-1", "2026-07-10"));
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(recordRepository.findByCreditAccountId(loan))
                .singleElement().satisfies(r -> {
                    assertThat(r.getAmount()).isEqualByComparingTo("540.00");
                    assertThat(r.getIvaAmount()).isEqualByComparingTo("86.40");
                    assertThat(r.getPeriod()).isEqualTo("202607");
                }));

        // 3. The run of July opens the lot: 10 % ISR (54.00) and two thirds of the IVA (57.60) withheld, 514.80 paid.
        assertThat(liquidation.runLiquidation("202607").lotsOpened()).isEqualTo(1);
        LiquidationBatch lot = lots.findByPeriod("202607").get(0);
        assertThat(lot.getNetAmount()).isEqualByComparingTo("514.80");
        JsonNode requested = json.readTree(lastValue(payouts, "commission.lot-payout-requested"));
        assertThat(requested.get("netAmount").decimalValue()).isEqualByComparingTo("514.80");
        assertThat(requested.get("beneficiaryAccount").asText()).isEqualTo("646180157000000004");
        assertThat(requested.get("itemRef").asText()).isEqualTo("LOTE:" + lot.getBatchId());
        assertThat(requested.get("attempt").asInt()).isEqualTo(1);
        assertThat(selfChecks.check(null).failures()).as("el lote recién abierto pasa su propia revisión").isEmpty();

        // 4. The rail rejects it: the lot waits. The next run requests it again (attempt 2) and the bank settles it.
        kafkaTemplate.send("disbursement.failed", lot.getBatchId().toString(), outcome(lot, 1, Map.of(
                "failureCode", "CUENTA_INEXISTENTE", "failureReason", "la cuenta no existe")));
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(lots.findById(lot.getBatchId()))
                .hasValueSatisfying(b -> assertThat(b.getStatus()).isEqualTo(LiquidationBatchStatus.PENDING)));
        assertThat(liquidation.runLiquidation("202608").retried()).isEqualTo(1);
        assertThat(json.readTree(lastValue(payouts, "commission.lot-payout-requested")).get("attempt").asInt()).isEqualTo(2);
        kafkaTemplate.send("disbursement.completed", lot.getBatchId().toString(), outcome(lot, 2, Map.of(
                "externalRef", "STP2026080500001", "settledAt", "2026-08-05T17:00:00Z")));
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> assertThat(lots.findById(lot.getBatchId()))
                .hasValueSatisfying(b -> {
                    assertThat(b.getStatus()).isEqualTo(LiquidationBatchStatus.CONFIRMED);
                    assertThat(b.getPaymentRef()).isEqualTo("STP2026080500001");
                }));
        assertThat(selfChecks.check(null).failures()).as("el lote reintentado y pagado pasa su revisión").isEmpty();

        // 5. The bank of the client returns the July payment: its commission, already paid, is clawed back.
        kafkaTemplate.send("credit-portfolio.balance-updated", loan.toString(),
                balance("evt-devolucion-1", loan, "PAYMENT_RETURNED", 3, "3000.00", "PAYMENT_RETURNED:pago-1", "2026-08-12"));
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            List<CommissionRecord> all = recordRepository.findByCreditAccountId(loan);
            assertThat(all).hasSize(2);
            CommissionRecord reversal = all.stream().filter(CommissionRecord::isReversal).findFirst().orElseThrow();
            assertThat(reversal.getAmount()).isEqualByComparingTo("-540.00");
            assertThat(reversal.getStatus()).isEqualTo(CommissionRecordStatus.ACCRUED);
            assertThat(reversal.getPeriod()).isEqualTo("202608");
        });
        assertThat(selfChecks.check(null).failures()).as("la reversa por recuperar pasa su revisión").isEmpty();
        payouts.close();
    }

    private static Map<String, Object> balance(String eventId, UUID loan, String trigger, long version,
                                               String interestDelta, String sourceEventId, String valueDate) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId", eventId);
        m.put("creditAccountId", loan.toString());
        m.put("triggerEvent", trigger);
        m.put("balanceVersion", version);
        m.put("accruedInterestBalance", 0);
        m.put("interestDelta", interestDelta);
        m.put("sourceEventId", sourceEventId);
        m.put("valueDate", valueDate);
        return m;
    }

    private static Map<String, Object> outcome(LiquidationBatch lot, int attempt, Map<String, Object> extra) {
        Map<String, Object> m = new HashMap<>(extra);
        m.put("sourceSystem", "commission");
        m.put("sourceReference", lot.itemRef());
        m.put("sourceMetadata", Map.of("batchId", lot.getBatchId().toString(), "attempt", String.valueOf(attempt)));
        return m;
    }

    private Consumer<String, String> consumerOf(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("flow-it-" + UUID.randomUUID(), "true", broker);
        Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(c, topic);
        return c;
    }

    private static String lastValue(Consumer<String, String> consumer, String topic) {
        ConsumerRecord<String, String> last = null;
        long until = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < until) {
            for (ConsumerRecord<String, String> r : KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2))) {
                if (topic.equals(r.topic())) last = r;
            }
            if (last != null) return last.value();
        }
        throw new AssertionError("nada publicado en " + topic);
    }
}
