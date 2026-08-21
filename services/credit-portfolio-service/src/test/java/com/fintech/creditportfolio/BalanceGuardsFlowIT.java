package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Acceptance IT — balance guards (post-check) and idempotency under real concurrency.
 *
 *   BG-1  Terminal account (WRITTEN_OFF) receives charge → no BalanceEvent saved
 *   BG-2  Overpayment → no BalanceEvent saved, principal unchanged
 *   BG-3  Normal charge after write-off does not corrupt settled balances
 *   BG-4  Concurrent duplicate charge events → exactly 1 BalanceEvent persisted (idempotency under race)
 *   BG-5  Concurrent distinct charges on same account → all BalanceEvents persisted, balance correct
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
            "credit-portfolio.balance-updated",
            "credit-portfolio.charge-rejected",
            "credit-portfolio.payment-rejected"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class BalanceGuardsFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("fintech.credit-portfolio.jwt-secret",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo");
    }

    @Autowired CreditAccountRepository accountRepository;
    @Autowired BalanceEventRepository balanceEventRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    private UUID persistActiveAccount(BigDecimal principal) {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), UUID.randomUUID().toString().substring(0, 18), UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                principal, null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        a.activate(principal);
        return accountRepository.save(a).getCreditAccountId();
    }

    private UUID persistWrittenOffAccount(BigDecimal principal) {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), UUID.randomUUID().toString().substring(0, 18), UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                principal, null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        a.activate(principal);
        a.executeWriteOff();
        return accountRepository.save(a).getCreditAccountId();
    }

    private Map<String, Object> chargeEvent(String eventId, UUID accountId, String type, double amount) {
        Map<String, Object> e = new HashMap<>();
        e.put("eventId", eventId);
        e.put("creditAccountId", accountId.toString());
        e.put("chargeType", type);
        e.put("totalAmount", amount);
        return e;
    }

    private Map<String, Object> paymentEvent(String eventId, UUID accountId, double amount) {
        Map<String, Object> e = new HashMap<>();
        e.put("eventId", eventId);
        e.put("creditAccountId", accountId.toString());
        e.put("amount", amount);
        return e;
    }

    // ── BG-1: terminal account → charge rejected (no BalanceEvent) ───────────────

    @Test
    void bg1_chargeOnWrittenOffAccount_noBalanceEventSaved() throws Exception {
        UUID accountId = persistWrittenOffAccount(new BigDecimal("50000"));
        long eventsBefore = balanceEventRepository.countByAccountId(accountId);

        kafkaTemplate.send("charges.charge-applied", accountId.toString(),
                chargeEvent("bg1-charge-" + UUID.randomUUID(), accountId, "ORDINARY_INTEREST", 1000))
                .get(5, TimeUnit.SECONDS);

        // Give consumer time to process
        Thread.sleep(3000);

        long eventsAfter = balanceEventRepository.countByAccountId(accountId);
        assertThat(eventsAfter).isEqualTo(eventsBefore)
                .as("BalanceEvent must NOT be saved for a terminal account");

        CreditAccount account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getStatus()).isEqualTo(CreditAccountStatus.WRITTEN_OFF);
        assertThat(account.getAccruedInterestBalance()).isEqualByComparingTo("0");
    }

    // ── BG-2: overpayment → payment rejected (no BalanceEvent) ───────────────────

    @Test
    void bg2_overpayment_noBalanceEventSaved_principalUnchanged() throws Exception {
        UUID accountId = persistActiveAccount(new BigDecimal("10000"));
        long eventsBefore = balanceEventRepository.countByAccountId(accountId);

        // Pay 99,999 when totalDebt = 10,000 → overpayment
        kafkaTemplate.send("payments.payment-applied", accountId.toString(),
                paymentEvent("bg2-pay-" + UUID.randomUUID(), accountId, 99999))
                .get(5, TimeUnit.SECONDS);

        Thread.sleep(3000);

        long eventsAfter = balanceEventRepository.countByAccountId(accountId);
        assertThat(eventsAfter).isEqualTo(eventsBefore)
                .as("BalanceEvent must NOT be saved for an overpayment");

        CreditAccount account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("10000");
    }

    // ── BG-3: write-off then charge → balance stays zero ─────────────────────────

    @Test
    void bg3_writeOffThenCharge_balanceRemainsZero() throws Exception {
        UUID accountId = persistActiveAccount(new BigDecimal("5000"));

        // Write off the account via Kafka
        Map<String, Object> writeOff = new HashMap<>();
        writeOff.put("eventId", "bg3-wo-" + UUID.randomUUID());
        writeOff.put("creditAccountId", accountId.toString());
        kafkaTemplate.send("collections.write-off-executed", accountId.toString(), writeOff).get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(accountRepository.findById(accountId).orElseThrow().getStatus())
                        .isEqualTo(CreditAccountStatus.WRITTEN_OFF));

        long eventsAfterWO = balanceEventRepository.countByAccountId(accountId);

        // Attempt a charge post-write-off
        kafkaTemplate.send("charges.charge-applied", accountId.toString(),
                chargeEvent("bg3-charge-" + UUID.randomUUID(), accountId, "MORATORIUM_INTEREST", 500))
                .get(5, TimeUnit.SECONDS);

        Thread.sleep(3000);

        // No new BalanceEvent from the rejected charge
        assertThat(balanceEventRepository.countByAccountId(accountId)).isEqualTo(eventsAfterWO);
        assertThat(accountRepository.findById(accountId).orElseThrow().getTotalDebt())
                .isEqualByComparingTo("0");
    }

    // ── BG-4: concurrent duplicate eventId → exactly 1 BalanceEvent ──────────────

    @Test
    void bg4_concurrentDuplicateChargeEvents_exactlyOneBalanceEventSaved()
            throws Exception, InterruptedException {

        UUID accountId = persistActiveAccount(new BigDecimal("50000"));
        String sharedEventId = "bg4-dup-" + UUID.randomUUID();

        // Fire 8 identical events in parallel
        int threads = 8;
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    gate.await();
                    kafkaTemplate.send("charges.charge-applied", accountId.toString(),
                            chargeEvent(sharedEventId, accountId, "ORDINARY_INTEREST", 1000))
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        gate.countDown();
        done.await();
        executor.shutdown();

        assertThat(errors.get()).isZero();

        // Wait for all deliveries to be processed
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            long count = balanceEventRepository.countByAccountId(accountId);
            assertThat(count).isEqualTo(1)
                    .as("Exactly 1 BalanceEvent must be saved despite 8 concurrent deliveries");
        });

        CreditAccount account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getAccruedInterestBalance()).isEqualByComparingTo("1000");
    }

    // ── BG-5: concurrent distinct charges on same account ────────────────────────

    @Test
    void bg5_concurrentDistinctCharges_allApplied_balanceSumsCorrectly()
            throws Exception, InterruptedException {

        UUID accountId = persistActiveAccount(new BigDecimal("100000"));

        int threads = 5;
        BigDecimal chargePerThread = new BigDecimal("1000");
        List<String> eventIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            eventIds.add("bg5-charge-" + i + "-" + UUID.randomUUID());
        }

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final String eventId = eventIds.get(i);
            executor.submit(() -> {
                try {
                    gate.await();
                    kafkaTemplate.send("charges.charge-applied", accountId.toString(),
                            chargeEvent(eventId, accountId, "ORDINARY_INTEREST", 1000))
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        gate.countDown();
        done.await();
        executor.shutdown();

        // All 5 charges (each 1,000) = 5,000 accrued interest
        BigDecimal expectedInterest = chargePerThread.multiply(BigDecimal.valueOf(threads));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            CreditAccount account = accountRepository.findById(accountId).orElseThrow();
            assertThat(account.getAccruedInterestBalance())
                    .isEqualByComparingTo(expectedInterest);
            assertThat(balanceEventRepository.countByAccountId(accountId))
                    .isEqualTo(threads);
        });
    }
}
