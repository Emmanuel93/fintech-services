package com.fintech.charges;

import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.application.service.AccrualScheduleService;
import com.fintech.charges.application.service.InterestAccrualService;
import com.fintech.charges.domain.AccrualScheduleStatus;
import com.fintech.charges.domain.ChargeType;
import com.fintech.charges.infrastructure.adapter.in.messaging.CreditAccountActivatedPayload;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "credit-portfolio.credit-account-activated",
            "credit-portfolio.balance-updated",
            "charges.charge-applied",
            "charges.charge-reversed"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class ChargesAcceptanceTest {

    @Autowired AccrualScheduleService scheduleService;
    @Autowired AccrualScheduleRepository scheduleRepo;
    @Autowired ChargeRecordRepository chargeRecordRepo;
    @Autowired InterestAccrualService accrualService;

    @Test
    void onCreditAccountActivated_createsScheduleAndOpeningFee(
            @Autowired org.springframework.kafka.test.EmbeddedKafkaBroker broker) {

        UUID accountId = UUID.randomUUID();
        UUID obligorId = UUID.randomUUID();

        var payload = new CreditAccountActivatedPayload(
                UUID.randomUUID().toString(), accountId, UUID.randomUUID(), obligorId,
                "PERSONAL_LOAN", "AMORTIZING",
                new BigDecimal("0.24"), new BigDecimal("0.36"), new BigDecimal("0.02"),
                new BigDecimal("10000"), new BigDecimal("10000"), "A", Instant.now());

        KafkaTemplate<String, Object> producer = buildProducer(broker.getBrokersAsString());
        producer.send("credit-portfolio.credit-account-activated", accountId.toString(), payload);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(scheduleRepo.findByCreditAccountId(accountId))
                    .isPresent()
                    .get()
                    .satisfies(s -> {
                        assertThat(s.getStatus()).isEqualTo(AccrualScheduleStatus.ACTIVE.name());
                        assertThat(s.getNominalRate()).isEqualByComparingTo(new BigDecimal("0.24"));
                    });
            // OPENING_FEE + IVA = 2 records
            assertThat(chargeRecordRepo.findAllByCreditAccountIdOrderByAccrualDateDesc(accountId))
                    .hasSize(2)
                    .anySatisfy(r -> assertThat(r.getChargeType()).isEqualTo(ChargeType.OPENING_FEE.name()))
                    .anySatisfy(r -> assertThat(r.getChargeType()).isEqualTo(ChargeType.IVA.name()));
        });
    }

    @Test
    void accrueInterest_persistsRecords() {
        UUID accountId = UUID.randomUUID();
        scheduleService.createFromActivation(accountId, UUID.randomUUID(),
                "PERSONAL_LOAN", "AMORTIZING",
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                new BigDecimal("10000"), BigDecimal.ZERO);

        var schedule = scheduleRepo.findByCreditAccountId(accountId).orElseThrow();
        LocalDate today = LocalDate.now();
        accrualService.accrueInterestForSchedule(schedule.getScheduleId(), today);

        var records = chargeRecordRepo.findAllByCreditAccountIdOrderByAccrualDateDesc(accountId);
        // opening-fee-rate=0.02 in test properties → createFromActivation creates OPENING_FEE + IVA (2)
        // accrueInterestForSchedule creates ORDINARY_INTEREST + IVA (2) → total 4
        assertThat(records)
                .hasSize(4)
                .anySatisfy(r -> assertThat(r.getChargeType()).isEqualTo(ChargeType.ORDINARY_INTEREST.name()))
                .anySatisfy(r -> assertThat(r.getChargeType()).isEqualTo(ChargeType.OPENING_FEE.name()));
    }

    private KafkaTemplate<String, Object> buildProducer(String bootstrapServers) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class
        );
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }
}
