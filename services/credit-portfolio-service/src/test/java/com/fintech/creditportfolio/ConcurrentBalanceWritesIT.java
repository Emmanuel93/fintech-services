package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.service.BalanceReconciliationService;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.PortfolioAssignedListener;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.PortfolioAssignedPayload;
import com.fintech.creditportfolio.infrastructure.job.DelinquencyCalculationJob;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subledger must explain every balance even when writers collide on the same account.
 *
 * <p>Two defects lost balance updates while their entries reached the ledger: unlocked writes (31 Oct,
 * overlapping accrual attempts) and, once the lock existed, a lock taken after the transaction had
 * already read the account (the overpayment check of a payment), which mutated the stale copy JPA held.
 * On the second clean run a returned payment and an applied payment 44 ms apart left a distributor line
 * $921 k off. These tests fire charges, payments and returned payments at one account from many threads
 * at once and require sum(balance_events) = principal + interest + IVA + penalty at the end.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class ConcurrentBalanceWritesIT {

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
    @Autowired BalanceReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired DelinquencyCalculationJob delinquencyJob;
    @Autowired PortfolioAssignedListener portfolioAssigned;

    private UUID activeAccount(String principal) {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), UUID.randomUUID().toString().substring(0, 18), UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal(principal), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        a.activate(new BigDecimal(principal));
        accountRepository.save(a);
        // The ledger starts where the debt is born (in production, the activation entry).
        jdbc.update("""
                INSERT INTO credit_portfolio.balance_events
                       (balance_event_id, credit_account_id, source_event_id, event_type, delta_amount,
                        principal_after, interest_after, penalty_after, applied_at, value_date)
                VALUES (gen_random_uuid(), ?, ?, 'ACTIVATION_DISBURSEMENT', ?, ?, 0, 0, now(), current_date)""",
                a.getCreditAccountId(), "activation:" + a.getCreditAccountId(), new BigDecimal(principal), new BigDecimal(principal));
        return a.getCreditAccountId();
    }

    private BigDecimal ledger(UUID accountId) {
        return jdbc.queryForObject("SELECT coalesce(sum(delta_amount), 0) FROM credit_portfolio.balance_events WHERE credit_account_id = ?",
                BigDecimal.class, accountId);
    }

    private BigDecimal debt(UUID accountId) {
        CreditAccount a = accountRepository.findById(accountId).orElseThrow();
        return a.getPrincipalBalance().add(a.getAccruedInterestBalance()).add(a.getIvaBalance()).add(a.getPenaltyBalance());
    }

    /** Runs every task at the same instant from its own thread and waits for all of them. */
    private static void together(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (Runnable task : tasks) {
            done.add(pool.submit(() -> {
                start.await();
                task.run();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    @Test
    @DisplayName("un pago y una devolución que llegan juntos: los dos quedan en el libro y en los saldos")
    void paymentAndReturnTogether() throws Exception {
        UUID account = activeAccount("20000");
        for (int round = 0; round < 25; round++) {
            String n = String.valueOf(round);
            // A return is always of a payment the portfolio already applied (same account key, same partition).
            reconciliation.onPaymentApplied("earlier-" + n + "-" + account, account, new BigDecimal("150.00"));
            together(List.of(
                    () -> reconciliation.onPaymentReturned("earlier-" + n + "-" + account, account, new BigDecimal("150.00")),
                    () -> reconciliation.onPaymentApplied("payment-" + n + "-" + account, account, new BigDecimal("100.00"))));
        }
        assertThat(ledger(account)).isEqualByComparingTo(debt(account));
        assertThat(debt(account)).as("20,000 − 25 × 100: every return undid its payment").isEqualByComparingTo("17500.00");
    }

    @Test
    @DisplayName("veinte escritores sobre una cuenta (cargos, IVA, pagos, devoluciones): libro = saldos, sin perder ninguno")
    void manyWritersOnOneAccount() throws Exception {
        UUID account = activeAccount("100000");
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String n = String.valueOf(i);
            if (i % 4 == 3) {
                // What gets returned was applied before.
                reconciliation.onPaymentApplied("earlier-" + n + "-" + account, account, new BigDecimal("75.00"));
            }
            switch (i % 4) {
                case 0 -> tasks.add(() -> reconciliation.onChargeApplied("interest-" + n + "-" + account, account, "ORDINARY_INTEREST", new BigDecimal("12.34")));
                case 1 -> tasks.add(() -> reconciliation.onChargeApplied("iva-" + n + "-" + account, account, "IVA", new BigDecimal("1.97")));
                case 2 -> tasks.add(() -> reconciliation.onPaymentApplied("payment-" + n + "-" + account, account, new BigDecimal("250.00")));
                default -> tasks.add(() -> reconciliation.onPaymentReturned("earlier-" + n + "-" + account, account, new BigDecimal("75.00")));
            }
        }
        long started = System.nanoTime();
        together(tasks);
        long millis = (System.nanoTime() - started) / 1_000_000;

        Integer entries = jdbc.queryForObject("SELECT count(*) FROM credit_portfolio.balance_events WHERE credit_account_id = ?",
                Integer.class, account);
        assertThat(entries).as("activation + 5 earlier payments + 20 entries").isEqualTo(26);
        assertThat(ledger(account)).isEqualByComparingTo(debt(account));
        // Serialized on the row lock, twenty writers still finish well within a night's budget.
        assertThat(millis).as("20 escrituras serializadas en una cuenta").isLessThan(10_000);
    }

    @Test
    @DisplayName("los escritores que guardan la cuenta completa (mora, sello de sucursal) no borran cargos que entran al mismo tiempo")
    void wholeAccountWritersDoNotPutBackOldBalances() throws Exception {
        UUID account = activeAccount("50000");
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String n = String.valueOf(i);
            tasks.add(() -> reconciliation.onChargeApplied("fee-" + n + "-" + account, account, "OPENING_FEE", new BigDecimal("15.00")));
            tasks.add(() -> delinquencyJob.runFor(BusinessClock.current().today()));
            tasks.add(() -> portfolioAssigned.onMessage(new PortfolioAssignedPayload(account, null, null, "S_CUL", 0)));
        }
        together(tasks);
        assertThat(ledger(account)).as("12 charges and the activation, all in the balances")
                .isEqualByComparingTo(debt(account));
        assertThat(debt(account)).isEqualByComparingTo("50180.00");
    }
}
