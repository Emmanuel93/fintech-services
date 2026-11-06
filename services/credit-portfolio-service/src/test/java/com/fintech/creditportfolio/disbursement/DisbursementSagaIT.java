package com.fintech.creditportfolio.disbursement;

import com.fintech.creditportfolio.application.port.in.SettleDisbursementUseCase;
import com.fintech.creditportfolio.application.port.in.SettleDisbursementUseCase.DisbursementFailure;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.service.BalanceReconciliationService;
import com.fintech.creditportfolio.application.service.PortfolioSelfCheckService;
import com.fintech.creditportfolio.domain.event.DispositionAuthorizedEvent;
import com.fintech.shared.selfcheck.SelfCheck;
import com.fintech.shared.selfcheck.SelfCheckReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

/**
 * The saga of a failed disbursement against a real Postgres (D63): a rule of the bank cancels the loan in the tables
 * (account, plan, disposition, ledger), the self-check follows it until charges reverses the opening fee and its IVA,
 * and a technical failure leaves the disposition in flight with its next attempt. Plus how long a wave of
 * cancellations and the self-check over them take.
 */
@SpringBootTest(properties = {
        "fintech.day-close.gate-wait=PT1S",
        "fintech.day-close.gate-poll=PT0.2S",
        "fintech.day-close.retry-cron=-",
        "fintech.day-close.allow-future-dates=true"
})
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "charges.day-accrued", "charges.charge-applied", "charges.charge-reversed", "credit-portfolio.day-closed",
        "credit-portfolio.balance-updated", "credit-portfolio.delinquency-status-updated",
        "credit-portfolio.installment-due", "credit-portfolio.installment-upcoming",
        "charges.account-accrued", "credit-portfolio.account-day-closed",
        "payments.payment-applied", "payments.payment-returned"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class DisbursementSagaIT {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired SettleDisbursementUseCase saga;
    @Autowired BalanceReconciliationService balances;
    @Autowired PortfolioSelfCheckService selfChecks;
    /** Kafka does not take part: what is published is asked of the publisher. */
    @MockitoBean CreditPortfolioEventPublisher publisher;

    private record Loan(UUID account, UUID disposition) {}

    /** Each case starts from an empty portfolio: what another left would be read by the self-check. */
    @BeforeEach
    void emptyPortfolio() {
        jdbc.update("DELETE FROM credit_portfolio.balance_events");
        jdbc.update("DELETE FROM credit_portfolio.installments");
        jdbc.update("DELETE FROM credit_portfolio.dispositions");
        jdbc.update("DELETE FROM credit_portfolio.credit_accounts");
    }

    /** A personal loan of 50,000 activated today, with its opening fee and IVA charged and a 12-entry plan. */
    private Loan loan() {
        UUID account = UUID.randomUUID();
        UUID disposition = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO credit_portfolio.credit_accounts (credit_account_id, contract_id, contract_number, product_code,
                    product_version, product_type, product_behavior, obligor_party_id, status, nominal_rate,
                    moratorium_rate, opening_fee_rate, principal_balance, accrued_interest_balance, penalty_balance,
                    iva_balance, days_delinquent, created_at, updated_at, activated_at, balance_version)
                VALUES (?, ?, ?, 'PL-001', 1, 'PERSONAL_LOAN', 'INSTALLMENT', ?, 'ACTIVE', 24.0, 36.0, 0.03, 50000, 0,
                        1500, 240, 0, ?, ?, ?, 3)""",
                account, UUID.randomUUID(), "CTR-" + account.toString().substring(0, 8), UUID.randomUUID(), now, now, now);
        jdbc.update("""
                INSERT INTO credit_portfolio.dispositions (disposition_id, credit_account_id, disposition_type, amount,
                    status, created_at, plan_mode, payee_name, payee_account, payee_account_type, payee_tax_id,
                    payee_concept)
                VALUES (?, ?, 'SELF_USE', 50000, 'PROCESSING', ?, 'AMORTIZED', 'JUANA PEREZ GOMEZ', '032180000118359719',
                        '40', 'PEGJ800101AB1', 'DISPOSICION DE CREDITO')""", disposition, account, now);
        List<Object[]> plan = new ArrayList<>();
        for (int n = 1; n <= 12; n++) {
            plan.add(new Object[]{UUID.randomUUID(), account, n, LocalDate.of(2026, 4, 1).plusMonths(n - 1),
                    new BigDecimal("4166.67"), new BigDecimal("1000.00"), BigDecimal.ZERO, new BigDecimal("5166.67"),
                    "PENDING"});
        }
        jdbc.batchUpdate("""
                INSERT INTO credit_portfolio.installments (installment_id, schedule_id, installment_number, due_date,
                    principal_amount, interest_amount, tax_amount, total_amount, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", plan);
        // Its ledger: the activation and the two charges, so the debt is what its movements add up to.
        event(account, "activation:" + account, "ACTIVATION_DISBURSEMENT", "50000");
        event(account, "fee-" + account, "CHARGE_OPENING_FEE", "1500");
        event(account, "iva-" + account, "CHARGE_IVA", "240");
        return new Loan(account, disposition);
    }

    private void event(UUID account, String key, String type, String delta) {
        jdbc.update("""
                INSERT INTO credit_portfolio.balance_events (balance_event_id, credit_account_id, source_event_id,
                    event_type, delta_amount, principal_after, interest_after, penalty_after, applied_at, value_date)
                VALUES (?, ?, ?, ?, ?, 0, 0, 0, ?, ?)""",
                UUID.randomUUID(), account, key, type, new BigDecimal(delta), Timestamp.from(Instant.now()),
                LocalDate.now());
    }

    private List<String> failures(SelfCheckReport r) {
        return r.failures().stream().map(SelfCheck::code).toList();
    }

    @Test
    @DisplayName("una regla del banco cancela el préstamo en la base, y la autoverificación lo sigue hasta que queda en cero")
    void aRuleCancelsTheLoanUntilItOwesNothing() {
        Loan loan = loan();

        saga.onDisbursementFailed(new DisbursementFailure(loan.disposition(), 1, "REJECTED_BY_PROVIDER",
                "-200 Se rechaza por PLD", false));

        assertThat(jdbc.queryForMap("SELECT status, principal_balance, cancellation_reason FROM credit_portfolio.credit_accounts "
                + "WHERE credit_account_id = ?", loan.account()))
                .containsEntry("status", "CANCELLED").containsEntry("cancellation_reason", "DISBURSEMENT_FAILED");
        assertThat(jdbc.queryForObject("SELECT principal_balance FROM credit_portfolio.credit_accounts "
                + "WHERE credit_account_id = ?", BigDecimal.class, loan.account())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_portfolio.installments WHERE schedule_id = ? "
                + "AND status = 'CANCELLED'", Integer.class, loan.account())).isEqualTo(12);
        assertThat(jdbc.queryForMap("SELECT status, failure_code FROM credit_portfolio.dispositions "
                + "WHERE disposition_id = ?", loan.disposition()))
                .containsEntry("status", "FAILED").containsEntry("failure_code", "REJECTED_BY_PROVIDER");
        assertThat(jdbc.queryForObject("SELECT principal_delta FROM credit_portfolio.balance_events "
                + "WHERE source_event_id = ?", BigDecimal.class, "DISPOSITION_FAILED:" + loan.disposition()))
                .isEqualByComparingTo("-50000");

        // The opening fee and its IVA are still owed until charges reverses them: the compensation is not over.
        assertThat(failures(selfChecks.check(null))).containsExactly("CANCELADAS_EN_CERO");

        balances.onChargeReversed("fee-" + loan.account(), loan.account(), "OPENING_FEE", new BigDecimal("1500"), false);
        balances.onChargeReversed("iva-" + loan.account(), loan.account(), "IVA", new BigDecimal("240"), false);

        // A charge that arrived after the cancellation is rejected; charges' compensation may still reverse it before
        // it hears the rejection. It never landed, so its reversal gives nothing back.
        balances.onChargeApplied("late-" + loan.account(), loan.account(), "ORDINARY_INTEREST", new BigDecimal("33.33"),
                null);
        balances.onChargeReversed("late-" + loan.account(), loan.account(), "ORDINARY_INTEREST", new BigDecimal("33.33"),
                false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_portfolio.balance_events WHERE source_event_id LIKE ?",
                Integer.class, "%late-" + loan.account())).as("ni el cargo tardío ni su reversa").isZero();

        SelfCheckReport after = selfChecks.check(null);
        assertThat(failures(after)).as("%s", after.checks()).isEmpty();
    }

    @Test
    @DisplayName("una falla técnica deja la disposición en vuelo con su siguiente intento y el crédito intacto")
    void aTechnicalFailureAsksAgain() {
        Loan loan = loan();

        saga.onDisbursementFailed(new DisbursementFailure(loan.disposition(), 1, "PROVIDER_TECHNICAL_FAILURE",
                "NO_REGISTRADA", true));

        assertThat(jdbc.queryForMap("SELECT status, attempt, failure_code FROM credit_portfolio.dispositions "
                + "WHERE disposition_id = ?", loan.disposition()))
                .containsEntry("status", "PROCESSING").containsEntry("attempt", 2)
                .containsEntry("failure_code", "PROVIDER_TECHNICAL_FAILURE");
        assertThat(jdbc.queryForObject("SELECT status FROM credit_portfolio.credit_accounts WHERE credit_account_id = ?",
                String.class, loan.account())).isEqualTo("ACTIVE");
        ArgumentCaptor<DispositionAuthorizedEvent> asked = ArgumentCaptor.forClass(DispositionAuthorizedEvent.class);
        then(publisher).should().publishDispositionAuthorized(asked.capture());
        assertThat(asked.getValue().dispositionId()).isEqualTo(loan.disposition());
        assertThat(asked.getValue().attempt()).isEqualTo(2);
        assertThat(asked.getValue().beneficiaryAccount()).isEqualTo("032180000118359719");
    }

    @Test
    @DisplayName("rendimiento: quinientas cancelaciones y la autoverificación sobre ellas, en segundos")
    void performance() {
        List<Loan> loans = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            loans.add(loan());
        }
        long started = System.nanoTime();
        for (Loan loan : loans) {
            saga.onDisbursementFailed(new DisbursementFailure(loan.disposition(), 1, "REJECTED_BY_PROVIDER", "x", false));
        }
        long sagaMs = (System.nanoTime() - started) / 1_000_000;

        long checkStarted = System.nanoTime();
        SelfCheckReport r = selfChecks.check(null);
        long checkMs = (System.nanoTime() - checkStarted) / 1_000_000;

        SelfCheck cancelled = r.checks().stream().filter(c -> c.code().equals("CANCELADAS_EN_CERO")).findFirst()
                .orElseThrow();
        assertThat(cancelled.items()).as("todavía deben su comisión").isEqualTo(500);
        assertThat(sagaMs).as("500 compensaciones en %d ms", sagaMs).isLessThan(30_000);
        assertThat(checkMs).as("autoverificación en %d ms", checkMs).isLessThan(5_000);
    }
}
