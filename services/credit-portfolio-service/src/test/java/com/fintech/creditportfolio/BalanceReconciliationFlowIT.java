package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Fase 2 IT — inbound charge/payment events drive the balance engine and republish BalanceUpdated.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "charges.charge-applied",
            "charges.charge-reversed",
            "payments.payment-applied",
            "payments.payment-returned",
            "collections.write-off-executed",
            "credit-portfolio.balance-updated"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class BalanceReconciliationFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("fintech.credit-portfolio.jwt-secret",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo");
    }

    @Autowired CreditAccountRepository accountRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    private UUID persistActiveAccount(String principal) {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), UUID.randomUUID().toString().substring(0, 18), UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal(principal), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        a.activate(new BigDecimal(principal));
        accountRepository.save(a);
        return a.getCreditAccountId();
    }

    @Test
    void chargeApplied_accruesInterest_onTheAccount() {
        UUID accountId = persistActiveAccount("50000");

        Map<String, Object> charge = new HashMap<>();
        charge.put("eventId", "it-charge-" + UUID.randomUUID());
        charge.put("creditAccountId", accountId.toString());
        charge.put("chargeType", "ORDINARY_INTEREST");
        charge.put("totalAmount", 1234.56);

        kafkaTemplate.send("charges.charge-applied", accountId.toString(), charge);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            CreditAccount a = accountRepository.findById(accountId).orElseThrow();
            assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("1234.56");
            assertThat(a.getTotalDebt()).isEqualByComparingTo("51234.56");
        });
    }

    @Test
    void paymentApplied_reducesDebtInHierarchy() {
        UUID accountId = persistActiveAccount("10000");

        // First accrue some interest
        Map<String, Object> charge = new HashMap<>();
        charge.put("eventId", "it-charge-" + UUID.randomUUID());
        charge.put("creditAccountId", accountId.toString());
        charge.put("chargeType", "ORDINARY_INTEREST");
        charge.put("totalAmount", 500);
        kafkaTemplate.send("charges.charge-applied", accountId.toString(), charge);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(accountRepository.findById(accountId).orElseThrow()
                        .getAccruedInterestBalance()).isEqualByComparingTo("500"));

        // Then pay 1500: 500 interest + 1000 principal
        Map<String, Object> payment = new HashMap<>();
        payment.put("eventId", "it-pay-" + UUID.randomUUID());
        payment.put("creditAccountId", accountId.toString());
        payment.put("amount", 1500);
        kafkaTemplate.send("payments.payment-applied", accountId.toString(), payment);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            CreditAccount a = accountRepository.findById(accountId).orElseThrow();
            assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("0");
            assertThat(a.getPrincipalBalance()).isEqualByComparingTo("9000");
        });
    }

    @Test
    void duplicateChargeEvent_isIdempotent() {
        UUID accountId = persistActiveAccount("50000");
        String eventId = "it-dup-" + UUID.randomUUID();

        Map<String, Object> charge = new HashMap<>();
        charge.put("eventId", eventId);
        charge.put("creditAccountId", accountId.toString());
        charge.put("chargeType", "ORDINARY_INTEREST");
        charge.put("totalAmount", 1000);

        // Same eventId twice → applied once
        kafkaTemplate.send("charges.charge-applied", accountId.toString(), charge);
        kafkaTemplate.send("charges.charge-applied", accountId.toString(), charge);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(accountRepository.findById(accountId).orElseThrow()
                        .getAccruedInterestBalance()).isEqualByComparingTo("1000"));

        // Give the second delivery time to (not) apply, then re-assert single application
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        assertThat(accountRepository.findById(accountId).orElseThrow()
                .getAccruedInterestBalance()).isEqualByComparingTo("1000");
    }
}
