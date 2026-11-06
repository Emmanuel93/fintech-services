package com.fintech.creditportfolio.dayclose;

import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository;
import com.fintech.creditportfolio.application.port.out.DaySnapshotRepository.AccountSnapshot;
import com.fintech.creditportfolio.application.service.PortfolioSelfCheckService;
import com.fintech.shared.selfcheck.SelfCheck;
import com.fintech.shared.selfcheck.SelfCheckReport;
import com.fintech.creditportfolio.infrastructure.job.DaySnapshotRunner;
import com.fintech.creditportfolio.infrastructure.job.DaySnapshotRunner.Outcome;
import org.junit.jupiter.api.BeforeEach;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-account day snapshot against a real Postgres: balances as of the end of D, delinquency as of D,
 * one snapshot per day, paged reads, and the time it takes for a portfolio-sized day.
 */
@SpringBootTest(properties = {
        "fintech.day-close.gate-wait=PT1S",
        "fintech.day-close.gate-poll=PT0.2S",
        "fintech.day-close.retry-cron=-",
        // The test picks its own dates; the "day has ended" gate is unit-tested in DaySnapshotRunnerTest.
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
class DaySnapshotIT {

    private static final ZoneId CDMX = ZoneId.of("America/Mexico_City");
    private static final LocalDate D = LocalDate.of(2027, 3, 10);

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
    @Autowired DaySnapshotRunner runner;
    @Autowired DaySnapshotRepository snapshots;
    @Autowired PortfolioSelfCheckService selfChecks;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM credit_portfolio.account_day_snapshots");
        jdbc.update("DELETE FROM credit_portfolio.day_snapshots");
        jdbc.update("DELETE FROM credit_portfolio.day_closes");
        jdbc.update("DELETE FROM credit_portfolio.installments");
        jdbc.update("DELETE FROM credit_portfolio.balance_events");
        jdbc.update("DELETE FROM credit_portfolio.dispositions");
        jdbc.update("DELETE FROM credit_portfolio.credit_accounts");
    }

    @Test
    @DisplayName("a payment valued on D+1, applied at 00:03, does not touch the snapshot of D; it shows in D+1's")
    void balancesAreAsOfTheEndOfD() {
        UUID account = account("20000.00", D.minusDays(30));
        event(account, "DISPOSITION_SELF_USE", "20000.00", "20000.00", "0.00", D.minusDays(30), D.minusDays(30).atStartOfDay(CDMX).toInstant());
        // D-1's interest, applied by the night chain of D at 00:05.
        event(account, "CHARGE_ORDINARY_INTEREST", "13.33", "20000.00", "13.33", D.minusDays(1), D.atTime(0, 5).atZone(CDMX).toInstant());
        // 00:03 of D+1, before the snapshot: the customer pays 8,000 of principal and the interest. Value date D+1.
        event(account, "PAYMENT_APPLIED", "-8013.33", "12000.00", "0.00", D.plusDays(1), D.plusDays(1).atTime(0, 3).atZone(CDMX).toInstant());
        balances(account, "12000.00", "0.00");

        assertThat(runner.take(D, "test").outcome()).isEqualTo(Outcome.TAKEN);
        AccountSnapshot atD = snapshots.find(D, account).orElseThrow();
        assertThat(atD.principal()).isEqualByComparingTo("20000.00");
        assertThat(atD.accruedInterest()).isEqualByComparingTo("13.33");
        assertThat(atD.laterMovements()).isEqualTo(1);

        assertThat(runner.take(D.plusDays(1), "test").outcome()).isEqualTo(Outcome.TAKEN);
        AccountSnapshot atD1 = snapshots.find(D.plusDays(1), account).orElseThrow();
        assertThat(atD1.principal()).isEqualByComparingTo("12000.00");
        assertThat(atD1.laterMovements()).isZero();
    }

    @Test
    @DisplayName("delinquency as of D: installments due before D and unpaid; one due on D is not late yet")
    void delinquencyIsAsOfD() {
        UUID account = account("10000.00", D.minusDays(60));
        installment(account, 1, D.minusDays(5), "1000.00", "PENDING");
        installment(account, 2, D, "1000.00", "PENDING");
        installment(account, 3, D.plusDays(25), "1000.00", "PENDING");
        installment(account, 0, D.minusDays(35), "1000.00", "PAID");

        runner.take(D, "test");
        AccountSnapshot atD = snapshots.find(D, account).orElseThrow();
        assertThat(atD.overduePrincipal()).isEqualByComparingTo("1000.00");
        assertThat(atD.overdueInstallments()).isEqualTo(1);
        assertThat(atD.oldestDueDate()).isEqualTo(D.minusDays(5));
        assertThat(atD.daysDelinquent()).isEqualTo(5);

        // The next day the installment due on D counts too: 2,000 overdue, 6 days from the oldest.
        runner.take(D.plusDays(1), "test");
        AccountSnapshot atD1 = snapshots.find(D.plusDays(1), account).orElseThrow();
        assertThat(atD1.overduePrincipal()).isEqualByComparingTo("2000.00");
        assertThat(atD1.daysDelinquent()).isEqualTo(6);
    }

    @Test
    @DisplayName("a statement-billed product is late on what its statements asked and was not paid, not on its plans' installments")
    void statementBilledProductIsLateOnItsStatements() {
        UUID line = account("30000.00", D.minusDays(60));
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET product_type = 'DISTRIBUTOR_LINE', product_behavior = 'REVOLVING', "
                + "accrued_interest_balance = 400.00 WHERE credit_account_id = ?", line);
        // Two statements asked 6,000 (due D-20) and 6,500 (due D-6); 9,000 was paid. A placement installment
        // still PENDING with its own date long gone does not count: the statements are what is owed.
        installment(line, 1, D.minusDays(20), "6000.00", "PAID");
        installment(line, 2, D.minusDays(6), "6500.00", "PARTIAL");
        jdbc.update("UPDATE credit_portfolio.installments SET interest_amount = 0, tax_amount = 0, total_amount = principal_amount WHERE schedule_id = ?", line);
        UUID placement = UUID.randomUUID();
        jdbc.update("INSERT INTO credit_portfolio.dispositions (disposition_id, credit_account_id, disposition_type, amount, status, "
                        + "source_event_id, created_at) VALUES (?, ?, 'THIRD_PARTY_CREDIT', 20000.00, 'COMPLETED', ?, ?)",
                placement, line, "placement-" + placement, Timestamp.from(D.minusDays(40).atTime(10, 0).atZone(CDMX).toInstant()));
        installment(placement, 1, D.minusDays(30), "5000.00", "PENDING");
        event(line, "PAYMENT_APPLIED", "-9000.00", "30000.00", "400.00", D.minusDays(7), D.minusDays(7).atTime(12, 0).atZone(CDMX).toInstant());

        runner.take(D, "test");
        AccountSnapshot atD = snapshots.find(D, line).orElseThrow();
        // 12,500 asked by D, 9,000 paid: 3,500 late; the oldest statement not fully covered is the second one.
        assertThat(atD.overduePrincipal()).isEqualByComparingTo("3500.00");
        assertThat(atD.oldestDueDate()).isEqualTo(D.minusDays(6));
        assertThat(atD.daysDelinquent()).isEqualTo(6);
    }

    @Test
    @DisplayName("the moratorium runs on the principal the unpaid statement billed, not on its interest, IVA or charges")
    void theMoratoriumBaseIsTheBilledPrincipalOnly() {
        UUID line = account("30000.00", D.minusDays(60));
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET product_type = 'DISTRIBUTOR_LINE', product_behavior = 'REVOLVING', "
                + "accrued_interest_balance = 1000.00 WHERE credit_account_id = ?", line);
        // Statement 1 (due D-20) asked 6,000 and was paid; statement 2 (due D-6) asked 6,500: a placement's installment
        // of 5,000 of principal, 1,000 of interest and 160 of IVA, plus 340 of earlier charges. Nothing paid of it.
        installment(line, 1, D.minusDays(20), "6000.00", "PAID");
        installment(line, 2, D.minusDays(6), "6500.00", "PENDING");
        jdbc.update("UPDATE credit_portfolio.installments SET interest_amount = 0, tax_amount = 0, total_amount = principal_amount, "
                + "cutoff_date = CASE installment_number WHEN 1 THEN ? ELSE ? END WHERE schedule_id = ?", D.minusDays(32), D.minusDays(18), line);
        UUID placement = UUID.randomUUID();
        jdbc.update("INSERT INTO credit_portfolio.dispositions (disposition_id, credit_account_id, disposition_type, amount, status, "
                        + "source_event_id, created_at) VALUES (?, ?, 'THIRD_PARTY_CREDIT', 20000.00, 'COMPLETED', ?, ?)",
                placement, line, "placement-" + placement, Timestamp.from(D.minusDays(40).atTime(10, 0).atZone(CDMX).toInstant()));
        installment(placement, 1, D.minusDays(6), "5000.00", "PENDING");
        jdbc.update("UPDATE credit_portfolio.installments SET interest_amount = 1000.00, tax_amount = 160.00, total_amount = 6160.00, "
                + "billed_cycle = 2 WHERE schedule_id = ?", placement);
        event(line, "PAYMENT_APPLIED", "-6000.00", "30000.00", "1000.00", D.minusDays(20), D.minusDays(20).atTime(12, 0).atZone(CDMX).toInstant());

        runner.take(D, "test");
        AccountSnapshot atD = snapshots.find(D, line).orElseThrow();
        // 6,500 unpaid, of which 5,000 is principal: the moratorium base; the delinquency counts from the statement's due date.
        assertThat(atD.overduePrincipal()).isEqualByComparingTo("5000.00");
        assertThat(atD.oldestDueDate()).isEqualTo(D.minusDays(6));
        assertThat(atD.daysDelinquent()).isEqualTo(6);
    }

    @Test
    @DisplayName("a payment covers only the statements issued before it: paying one in full does not pay ahead the next minimum")
    void aFullPaymentDoesNotPayAheadTheNextStatement() {
        UUID card = account("7294.64", D.minusDays(90));
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET product_type = 'CREDIT_CARD', product_behavior = 'REVOLVING' "
                + "WHERE credit_account_id = ?", card);
        // Statement 1 (cutoff D-68, due D-48) asked 517.40; statement 2 (cutoff D-36, due D-15) asked 217.97.
        installment(card, 1, D.minusDays(48), "517.40", "PENDING");
        installment(card, 2, D.minusDays(15), "217.97", "PENDING");
        jdbc.update("UPDATE credit_portfolio.installments SET interest_amount = 0, tax_amount = 0, total_amount = principal_amount, "
                + "cutoff_date = CASE installment_number WHEN 1 THEN ? ELSE ? END WHERE schedule_id = ?", D.minusDays(68), D.minusDays(36), card);
        // The whole balance of statement 1 paid on its due date, before statement 2 was cut; nothing since.
        event(card, "PAYMENT_APPLIED", "-10348.00", "7294.64", "0", D.minusDays(48), D.minusDays(48).atTime(12, 0).atZone(CDMX).toInstant());

        runner.take(D, "test");
        AccountSnapshot atD = snapshots.find(D, card).orElseThrow();
        assertThat(atD.overduePrincipal()).isEqualByComparingTo("217.97");
        assertThat(atD.oldestDueDate()).isEqualTo(D.minusDays(15));
        assertThat(atD.daysDelinquent()).isEqualTo(15);
    }

    @Test
    @DisplayName("what a statement asked to pay on D and D's end left unpaid is not late on D, and the snapshot carries it "
            + "apart: a statement cut on D asks for it as arrears; on D+1 it is overdue")
    void whatFallsDueOnDIsCarriedApart() {
        UUID line = account("30000.00", D.minusDays(60));
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET product_type = 'DISTRIBUTOR_LINE', product_behavior = 'REVOLVING' "
                + "WHERE credit_account_id = ?", line);
        // Statement 1 (cutoff D-30) asked 6,000 by D-15 and was paid; statement 2 (cutoff D-15) asked 3,268.44 by D, the
        // next statement's cutoff, as a distributor line's statements do, and nothing came.
        installment(line, 1, D.minusDays(15), "6000.00", "PAID");
        installment(line, 2, D, "3268.44", "PENDING");
        jdbc.update("UPDATE credit_portfolio.installments SET interest_amount = 0, tax_amount = 0, total_amount = principal_amount, "
                + "cutoff_date = CASE installment_number WHEN 1 THEN ? ELSE ? END WHERE schedule_id = ?", D.minusDays(30), D.minusDays(15), line);
        event(line, "PAYMENT_APPLIED", "-6000.00", "30000.00", "0", D.minusDays(16), D.minusDays(16).atTime(12, 0).atZone(CDMX).toInstant());

        runner.take(D, "test");
        AccountSnapshot atD = snapshots.find(D, line).orElseThrow();
        assertThat(atD.overduePrincipal()).isEqualByComparingTo("0");
        assertThat(atD.daysDelinquent()).isZero();
        assertThat(atD.statementDueUnpaid()).isEqualByComparingTo("3268.44");

        runner.take(D.plusDays(1), "test");
        AccountSnapshot atD1 = snapshots.find(D.plusDays(1), line).orElseThrow();
        assertThat(atD1.overduePrincipal()).isEqualByComparingTo("3268.44");
        assertThat(atD1.daysDelinquent()).isEqualTo(1);
        assertThat(atD1.statementDueUnpaid()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the snapshot carries the interest the plans set for the periods falling due on D, for per-period products")
    void scheduledInterestOfThePeriodsFallingDue() {
        Instant beforeD = D.minusDays(20).atTime(12, 0).atZone(CDMX).toInstant();
        UUID line = account("40000.00", D.minusDays(60));
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET product_type = 'DISTRIBUTOR_LINE', product_behavior = 'REVOLVING' "
                + "WHERE credit_account_id = ?", line);
        // Two placements with an installment falling due on D (100 of interest each; one already paid with the
        // statement that billed it, which does not make its period free), one due later, one a restructure replaced.
        UUID a = placement(line, "20000.00", beforeD);
        UUID b = placement(line, "20000.00", beforeD);
        installment(a, 1, D, "1000.00", "PAID");
        installment(b, 1, D, "1000.00", "PENDING");
        installment(a, 2, D.plusDays(14), "1000.00", "PENDING");
        installment(b, 9, D, "1000.00", "REPLACED");
        // The line's own cycle installment carries no interest of its own.
        installment(line, 1, D, "2000.00", "PENDING");
        jdbc.update("UPDATE credit_portfolio.installments SET interest_amount = 0 WHERE schedule_id = ?", line);
        // A line with nothing owed: its plan's period is not charged (paid off before it ran).
        UUID paidOff = account("0.00", D.minusDays(60));
        UUID c = placement(paidOff, "5000.00", beforeD);
        installment(c, 1, D, "1000.00", "PAID");
        // A period that ended on D but whose installment billing moved to a later due date still ends on D; one that
        // ends later but was billed with a due date of D does not.
        installment(a, 3, D.plusDays(10), "1000.00", "PENDING");
        jdbc.update("UPDATE credit_portfolio.installments SET original_due_date = ? WHERE schedule_id = ? AND installment_number = 3",
                D, a);
        installment(b, 3, D, "1000.00", "PENDING");
        jdbc.update("UPDATE credit_portfolio.installments SET original_due_date = ? WHERE schedule_id = ? AND installment_number = 3",
                D.plusDays(12), b);
        // A placement made after the end of D is not in D's facts.
        UUID tomorrow = placement(line, "5000.00", D.plusDays(1).atTime(9, 0).atZone(CDMX).toInstant());
        installment(tomorrow, 1, D, "500.00", "PENDING");

        // The second placement's period ends on D with a tax of its own, rounded apart: the snapshot adds the plans'
        // taxes as their clients pay them (16.00 + 16.00 + 16.01), not 16 % of the total interest.
        jdbc.update("UPDATE credit_portfolio.installments SET tax_amount = 16.01 WHERE schedule_id = ? AND installment_number = 1", b);

        runner.take(D, "test");
        assertThat(snapshots.find(D, line).orElseThrow().scheduledInterest()).isEqualByComparingTo("300.00");
        assertThat(snapshots.find(D, line).orElseThrow().scheduledTax()).isEqualByComparingTo("48.01");
        assertThat(snapshots.find(D, paidOff).orElseThrow().scheduledInterest()).isEqualByComparingTo("0.00");
        assertThat(snapshots.find(D, paidOff).orElseThrow().scheduledTax()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a placement paid off before D owes no interest on D, even once a new placement gives the line principal again")
    void aPlacementPaidOffBeforeItsPeriodOwesNoInterest() {
        UUID line = account("8000.00", D.minusDays(200));
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET product_type = 'DISTRIBUTOR_LINE', product_behavior = 'REVOLVING' "
                + "WHERE credit_account_id = ?", line);
        // Paid off with the whole line 20 days before D: its installments still on the plan were settled, never billed.
        UUID vieja = placement(line, "10000.00", D.minusDays(150).atTime(12, 0).atZone(CDMX).toInstant());
        installment(vieja, 17, D, "477.59", "PAID");
        settled(vieja, 17, D.minusDays(20), null);
        // A new placement nine days before D: its first period ends on D.
        UUID nueva = placement(line, "8000.00", D.minusDays(9).atTime(12, 0).atZone(CDMX).toInstant());
        installment(nueva, 1, D, "646.05", "PENDING");
        // An installment a statement billed and the holder paid before D still owes its period (paid with its interest).
        UUID facturada = placement(line, "3000.00", D.minusDays(40).atTime(12, 0).atZone(CDMX).toInstant());
        installment(facturada, 2, D, "300.00", "PAID");
        settled(facturada, 2, D.minusDays(1), 9);

        // An installment loan whose client paid an installment ahead paid its interest with it: still charged on D.
        UUID prestamo = account("5000.00", D.minusDays(90));
        UUID desembolso = placement(prestamo, "5000.00", D.minusDays(90).atTime(12, 0).atZone(CDMX).toInstant());
        installment(desembolso, 3, D, "800.00", "PAID");
        settled(desembolso, 3, D.minusDays(5), null);

        runner.take(D, "test");
        assertThat(snapshots.find(D, line).orElseThrow().scheduledInterest()).isEqualByComparingTo("200.00");
        assertThat(snapshots.find(D, line).orElseThrow().scheduledTax()).isEqualByComparingTo("32.00");
        assertThat(snapshots.find(D, prestamo).orElseThrow().scheduledInterest()).isEqualByComparingTo("100.00");
    }

    /** The day an installment became paid and the statement cycle that billed it (null: none). */
    private void settled(UUID schedule, int number, LocalDate paidOn, Integer billedCycle) {
        jdbc.update("UPDATE credit_portfolio.installments SET paid_on = ?, billed_cycle = ? WHERE schedule_id = ? AND installment_number = ?",
                paidOn, billedCycle, schedule, number);
    }

    private UUID placement(UUID line, String amount, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO credit_portfolio.dispositions (disposition_id, credit_account_id, disposition_type, amount, status, "
                        + "source_event_id, created_at) VALUES (?, ?, 'THIRD_PARTY_CREDIT', ?, 'COMPLETED', ?, ?)",
                id, line, new BigDecimal(amount), "placement-" + id, Timestamp.from(at));
        return id;
    }

    @Test
    @DisplayName("an interest-free deferred purchase does not bear the line's interest while its installments are not due")
    void interestFreeDeferralLeavesTheInterestBasis() {
        Instant beforeD = D.minusDays(10).atTime(12, 0).atZone(CDMX).toInstant();
        // A card owing 9,000 of principal: 3,000 revolving, 5,000 left of a 6,000 purchase deferred interest-free
        // (one installment paid, one due and unpaid, four to come) and 1,000 deferred with interest of its own.
        UUID card = account("9000.00", D.minusDays(60));
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET product_type = 'CREDIT_CARD', product_behavior = 'REVOLVING' "
                + "WHERE credit_account_id = ?", card);
        UUID msi = deferral(card, "6000.00", beforeD);
        installment(msi, 1, D.minusDays(35), "1000.00", "PAID");
        installment(msi, 2, D.minusDays(5), "1000.00", "PENDING");
        for (int n = 3; n <= 6; n++) installment(msi, n, D.plusDays(30L * (n - 2) - 5), "1000.00", "PENDING");
        interestFree(msi);
        UUID withInterest = deferral(card, "1000.00", beforeD);
        installment(withInterest, 1, D.plusDays(25), "500.00", "PENDING");
        installment(withInterest, 2, D.plusDays(55), "500.00", "PENDING");
        // A purchase deferred after the end of D is not in D's basis yet.
        UUID later = account("3000.00", D.minusDays(60));
        UUID tomorrow = deferral(later, "3000.00", D.plusDays(1).atTime(9, 0).atZone(CDMX).toInstant());
        for (int n = 1; n <= 3; n++) installment(tomorrow, n, D.plusDays(30L * n), "1000.00", "PENDING");
        interestFree(tomorrow);
        UUID loan = account("12000.00", D.minusDays(60));
        installment(loan, 1, D.plusDays(20), "1000.00", "PENDING");

        runner.take(D, "test");
        // The overdue interest-free installment bears interest again; the four to come do not.
        assertThat(snapshots.find(D, card).orElseThrow().interestBearingPrincipal()).isEqualByComparingTo("5000.00");
        assertThat(snapshots.find(D, card).orElseThrow().principal()).isEqualByComparingTo("9000.00");
        assertThat(snapshots.find(D, later).orElseThrow().interestBearingPrincipal()).isEqualByComparingTo("3000.00");
        assertThat(snapshots.find(D, loan).orElseThrow().interestBearingPrincipal()).isEqualByComparingTo("12000.00");
    }

    @Test
    @DisplayName("delinquency is measured on principal: an account ahead of its plan is current even if an installment is still PARTIAL")
    void delinquencyIsMeasuredOnPrincipal() {
        // Owes 500 of principal; the plan still shows a 1,000 installment overdue (PARTIAL) and 1,000 to come.
        UUID ahead = account("500.00", D.minusDays(60));
        installment(ahead, 1, D.minusDays(5), "1000.00", "PARTIAL");
        installment(ahead, 2, D.plusDays(25), "1000.00", "PENDING");
        // Owes 1,500: 1,000 is not due yet, so 500 is overdue, and it belongs to the newest overdue installment.
        UUID behind = account("1500.00", D.minusDays(60));
        installment(behind, 1, D.minusDays(35), "1000.00", "PARTIAL");
        installment(behind, 2, D.minusDays(5), "1000.00", "PENDING");
        installment(behind, 3, D.plusDays(25), "1000.00", "PENDING");

        runner.take(D, "test");
        AccountSnapshot a = snapshots.find(D, ahead).orElseThrow();
        assertThat(a.overduePrincipal()).isEqualByComparingTo("0.00");
        assertThat(a.daysDelinquent()).isZero();
        AccountSnapshot b = snapshots.find(D, behind).orElseThrow();
        assertThat(b.overduePrincipal()).isEqualByComparingTo("500.00");
        assertThat(b.oldestDueDate()).isEqualTo(D.minusDays(5));
        assertThat(b.daysDelinquent()).isEqualTo(5);
    }

    @Test
    @DisplayName("a write-off valued after D does not leave a false ledger gap: the IVA it zeroed is put back in D's balances")
    void laterWriteOffRestoresIvaInTheSnapshot() {
        UUID account = account("0.00", D.minusDays(60));
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET status = 'WRITTEN_OFF' WHERE credit_account_id = ?", account);
        Instant earlier = D.minusDays(1).plusDays(1).atTime(0, 4).atZone(CDMX).toInstant();
        event(account, "ACTIVATION_DISBURSEMENT", "1000.00", "1000.00", "0.00", D.minusDays(10), earlier.minusSeconds(86_400L * 9));
        event(account, "CHARGE_ORDINARY_INTEREST", "50.00", "1000.00", "50.00", D.minusDays(1), earlier);
        event(account, "CHARGE_IVA", "8.00", "1000.00", "50.00", D.minusDays(1), earlier.plusSeconds(1));
        // Written off after midnight: valued D+1, it zeroed principal, interest and the 8.00 of IVA.
        event(account, "WRITE_OFF", "-1058.00", "0.00", "0.00", D.plusDays(1), D.plusDays(1).atTime(0, 3).atZone(CDMX).toInstant());

        runner.take(D, "test");
        AccountSnapshot atD = snapshots.find(D, account).orElseThrow();
        assertThat(atD.iva()).isEqualByComparingTo("8.00");
        assertThat(atD.totalDebt()).isEqualByComparingTo("1058.00");
        assertThat(atD.ledgerGap()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("the snapshot checks the subledger: a balance moved without a ledger entry shows up as a gap and is counted")
    void reportsLedgerGaps() {
        UUID intact = account("20000.00", D.minusDays(30));
        event(intact, "DISPOSITION_SELF_USE", "20000.00", "20000.00", "0.00", D.minusDays(30), D.minusDays(30).atStartOfDay(CDMX).toInstant());
        UUID drifted = account("20000.00", D.minusDays(30));
        event(drifted, "DISPOSITION_SELF_USE", "20000.00", "20000.00", "0.00", D.minusDays(30), D.minusDays(30).atStartOfDay(CDMX).toInstant());
        // Somebody raised the interest balance by 13.33 without an entry (the lost-update defect of 31 Oct, in reverse).
        balances(drifted, "20000.00", "13.33");

        var r = runner.take(D, "test");

        assertThat(snapshots.find(D, intact).orElseThrow().ledgerGap()).isEqualByComparingTo("0.00");
        AccountSnapshot off = snapshots.find(D, drifted).orElseThrow();
        assertThat(off.ledgerTotal()).isEqualByComparingTo("20000.00");
        assertThat(off.ledgerGap()).isEqualByComparingTo("-13.33");
        assertThat(r.snapshot().ledgerGaps()).isEqualTo(1);
    }

    @Test
    @DisplayName("the portfolio's self-check catches the drift twice: the balance against its ledger, and the photo's gap")
    void selfChecksCatchTheDrift() {
        UUID intact = account("20000.00", D.minusDays(30));
        event(intact, "DISPOSITION_SELF_USE", "20000.00", "20000.00", "0.00", D.minusDays(30), D.minusDays(30).atStartOfDay(CDMX).toInstant());
        runner.take(D, "test");
        SelfCheckReport clean = selfChecks.check(null);
        assertThat(clean.failures()).as("%s", clean.checks()).isEmpty();

        UUID drifted = account("20000.00", D.minusDays(30));
        event(drifted, "DISPOSITION_SELF_USE", "20000.00", "20000.00", "0.00", D.minusDays(30), D.minusDays(30).atStartOfDay(CDMX).toInstant());
        balances(drifted, "20000.00", "13.33");
        jdbc.update("DELETE FROM credit_portfolio.account_day_snapshots");
        jdbc.update("DELETE FROM credit_portfolio.day_snapshots");
        runner.take(D, "test");

        SelfCheckReport r = selfChecks.check(null);
        assertThat(r.failures()).extracting(SelfCheck::code)
                .containsExactlyInAnyOrder("SALDO_IGUAL_A_SU_LIBRO", "FOTO_SIN_BRECHA");
        assertThat(r.failures()).allSatisfy(c -> assertThat(c.amount()).isEqualByComparingTo("13.33"));
        assertThat(selfChecks.check(D.toString().substring(0, 7).replace("-", "")).failures())
                .extracting(SelfCheck::code).contains("FOTO_SIN_BRECHA");
    }

    @Test
    @DisplayName("an account activated after D, or settled before it, is not in the snapshot of D")
    void onlyAccountsAliveAtTheEndOfD() {
        UUID alive = account("5000.00", D.minusDays(1));
        UUID later = account("5000.00", D.plusDays(1));
        UUID settled = account("0.00", D.minusDays(90));
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET status = 'SETTLED' WHERE credit_account_id = ?", settled);

        runner.take(D, "test");

        assertThat(snapshots.find(D, alive)).isPresent();
        assertThat(snapshots.find(D, later)).isEmpty();
        assertThat(snapshots.find(D, settled)).isEmpty();
        assertThat(snapshots.find(D).orElseThrow().accounts()).isEqualTo(1);
    }

    @Test
    @DisplayName("taken once: a second request returns the same snapshot; pages, lookups and history read it back")
    void idempotentAndReadable() {
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 5; i++) accounts.add(account("1000.00", D.minusDays(10)));

        assertThat(runner.take(D, "test").outcome()).isEqualTo(Outcome.TAKEN);
        assertThat(runner.take(D, "test").outcome()).isEqualTo(Outcome.ALREADY_TAKEN);
        assertThat(snapshots.find(D).orElseThrow().accounts()).isEqualTo(5);

        List<AccountSnapshot> first = snapshots.page(D, null, 2);
        List<AccountSnapshot> second = snapshots.page(D, first.get(1).creditAccountId(), 2);
        List<AccountSnapshot> third = snapshots.page(D, second.get(1).creditAccountId(), 2);
        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(third).hasSize(1);
        assertThat(snapshots.lookup(D, accounts.subList(0, 3))).hasSize(3);

        runner.take(D.plusDays(1), "test");
        assertThat(snapshots.history(accounts.get(0), D, D.plusDays(1))).hasSize(2);
    }

    @Test
    @DisplayName("the snapshot of D waits for D-1 to be closed in the portfolio")
    void waitsForThePreviousDay() {
        account("1000.00", D.minusDays(10));
        jdbc.update("INSERT INTO credit_portfolio.day_closes (business_date, delinquency_as_of, status, triggered_by, waiting_for) VALUES (?, ?, 'WAITING', 'x', 'cifras')",
                D.minusDays(1), D);

        var r = runner.take(D, "test");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("falta cerrar el " + D.minusDays(1));
        assertThat(snapshots.find(D).orElseThrow().status()).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("performance: 5,000 accounts with history and installments are photographed in under 5 seconds")
    void performance() {
        int n = 5_000;
        List<Object[]> accountRows = new ArrayList<>();
        List<Object[]> eventRows = new ArrayList<>();
        List<Object[]> installmentRows = new ArrayList<>();
        Instant activated = D.minusDays(45).atStartOfDay(CDMX).toInstant();
        for (int i = 0; i < n; i++) {
            UUID id = UUID.randomUUID();
            accountRows.add(accountRow(id, "15000.00", activated));
            for (int k = 0; k < 3; k++) {
                eventRows.add(new Object[]{UUID.randomUUID(), id, "perf-" + id + "-" + k, "CHARGE_ORDINARY_INTEREST",
                        new BigDecimal("10.00"), new BigDecimal("15000.00"), new BigDecimal(10 * (k + 1)), BigDecimal.ZERO,
                        Timestamp.from(activated.plusSeconds(86400L * (k + 1))), D.minusDays(44 - k)});
            }
            // A tenth of the portfolio is late by one installment.
            installmentRows.add(new Object[]{UUID.randomUUID(), id, 1, D.minusDays(15), new BigDecimal("1250.00"),
                    new BigDecimal("300.00"), new BigDecimal("48.00"), new BigDecimal("1598.00"), i % 10 == 0 ? "PENDING" : "PAID"});
            installmentRows.add(new Object[]{UUID.randomUUID(), id, 2, D.plusDays(15), new BigDecimal("1250.00"),
                    new BigDecimal("290.00"), new BigDecimal("46.40"), new BigDecimal("1586.40"), "PENDING"});
        }
        jdbc.batchUpdate(ACCOUNT_INSERT, accountRows);
        jdbc.batchUpdate("""
                INSERT INTO credit_portfolio.balance_events (balance_event_id, credit_account_id, source_event_id, event_type,
                    delta_amount, principal_after, interest_after, penalty_after, applied_at, value_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", eventRows);
        jdbc.batchUpdate("""
                INSERT INTO credit_portfolio.installments (installment_id, schedule_id, installment_number, due_date,
                    principal_amount, interest_amount, tax_amount, total_amount, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", installmentRows);

        long started = System.nanoTime();
        var r = runner.take(D, "perf");
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(r.outcome()).isEqualTo(Outcome.TAKEN);
        assertThat(r.snapshot().accounts()).isEqualTo(n);
        assertThat(r.snapshot().delinquentAccounts()).isEqualTo(n / 10);
        assertThat(r.snapshot().principalTotal()).isEqualByComparingTo(new BigDecimal("15000.00").multiply(BigDecimal.valueOf(n)));
        System.out.printf("Foto de %d cuentas (%d eventos, %d cuotas) en %d ms%n", n, eventRows.size(), installmentRows.size(), ms);
        assertThat(ms).isLessThan(5_000);

        // The portfolio's self-check over the same day: every account, every event, every photo.
        long checkStarted = System.nanoTime();
        SelfCheckReport report = selfChecks.check(null);
        long checkMs = (System.nanoTime() - checkStarted) / 1_000_000;
        System.out.printf("Autoverificación de %d cuentas en %d ms%n", n, checkMs);
        // Six checks: the sixth (D63) is that every credit cancelled because its disbursement failed owes nothing.
        assertThat(report.checks()).hasSize(6);
        assertThat(report.checks()).filteredOn(c -> c.code().equals("CANCELADAS_EN_CERO"))
                .singleElement().satisfies(c -> assertThat(c.ok()).isTrue());
        assertThat(checkMs).as("autoverificación de %d cuentas en %d ms", n, checkMs).isLessThan(5_000);
    }

    // --- fixtures -------------------------------------------------------------------------------------

    private static final String ACCOUNT_INSERT = """
            INSERT INTO credit_portfolio.credit_accounts (credit_account_id, contract_id, contract_number, product_code,
                product_version, product_type, product_behavior, obligor_party_id, status, nominal_rate, moratorium_rate,
                opening_fee_rate, principal_balance, accrued_interest_balance, penalty_balance, iva_balance, days_delinquent,
                created_at, updated_at, activated_at, balance_version)
            VALUES (?, ?, ?, 'PL-001', 1, 'PERSONAL_LOAN', 'INSTALLMENT', ?, 'ACTIVE', 24.0, 36.0, 0, ?, 0, 0, 0, 0, ?, ?, ?, 1)""";

    @Test
    @DisplayName("the day a line ends (closed after a quita) it is photographed once more, with what it ends with; not after")
    void anAccountThatEndsIsPhotographedOnceMore() {
        UUID account = account("0.00", D.minusDays(60));
        Timestamp closedAt = Timestamp.from(D.atTime(10, 0).atZone(CDMX).toInstant());
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET status = 'CLOSED', closure_reason = 'QUITA', closed_at = ?, "
                + "updated_at = ?, principal_balance = 0, available_credit = 0, days_delinquent = 0 WHERE credit_account_id = ?",
                closedAt, closedAt, account);

        assertThat(runner.take(D, "test").outcome()).isEqualTo(Outcome.TAKEN);
        AccountSnapshot atD = snapshots.find(D, account).orElseThrow();
        assertThat(atD.accountStatus()).isEqualTo("CLOSED");
        assertThat(atD.principal()).isEqualByComparingTo("0");
        assertThat(atD.daysDelinquent()).isZero();
        assertThat(atD.overdueInstallments()).isZero();

        // Ended, it is not photographed again: the last photo is the day it ended.
        runner.take(D.plusDays(1), "test");
        assertThat(snapshots.find(D.plusDays(1), account)).isEmpty();
    }

    private UUID account(String principal, LocalDate activatedOn) {
        UUID id = UUID.randomUUID();
        jdbc.update(ACCOUNT_INSERT, accountRow(id, principal, activatedOn.atTime(10, 0).atZone(CDMX).toInstant()));
        return id;
    }

    private static Object[] accountRow(UUID id, String principal, Instant activatedAt) {
        Timestamp at = Timestamp.from(activatedAt);
        return new Object[]{id, UUID.randomUUID(), "CTR-" + id.toString().substring(0, 8), UUID.randomUUID(),
                new BigDecimal(principal), at, at, at};
    }

    private void balances(UUID account, String principal, String interest) {
        jdbc.update("UPDATE credit_portfolio.credit_accounts SET principal_balance = ?, accrued_interest_balance = ? WHERE credit_account_id = ?",
                new BigDecimal(principal), new BigDecimal(interest), account);
    }

    private void event(UUID account, String type, String delta, String principalAfter, String interestAfter,
                       LocalDate valueDate, Instant appliedAt) {
        jdbc.update("""
                INSERT INTO credit_portfolio.balance_events (balance_event_id, credit_account_id, source_event_id, event_type,
                    delta_amount, principal_after, interest_after, penalty_after, applied_at, value_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)""",
                UUID.randomUUID(), account, UUID.randomUUID().toString(), type, new BigDecimal(delta),
                new BigDecimal(principalAfter), new BigDecimal(interestAfter), Timestamp.from(appliedAt), valueDate);
    }

    private UUID deferral(UUID account, String amount, Instant deferredAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO credit_portfolio.dispositions (disposition_id, credit_account_id, disposition_type, amount, status, "
                        + "source_event_id, plan_mode, deferred_at, deferred_term, created_at) "
                        + "VALUES (?, ?, 'SELF_USE', ?, 'COMPLETED', ?, 'AMORTIZED', ?, 6, ?)",
                id, account, new BigDecimal(amount), "purchase-" + id, Timestamp.from(deferredAt), Timestamp.from(deferredAt));
        return id;
    }

    private void interestFree(UUID schedule) {
        jdbc.update("UPDATE credit_portfolio.installments SET interest_amount = 0, tax_amount = 0, total_amount = principal_amount "
                + "WHERE schedule_id = ?", schedule);
    }

    private void installment(UUID account, int number, LocalDate dueDate, String principal, String status) {
        jdbc.update("""
                INSERT INTO credit_portfolio.installments (installment_id, schedule_id, installment_number, due_date,
                    principal_amount, interest_amount, tax_amount, total_amount, status)
                VALUES (?, ?, ?, ?, ?, 100.00, 16.00, ?, ?)""",
                UUID.randomUUID(), account, number, dueDate, new BigDecimal(principal),
                new BigDecimal(principal).add(new BigDecimal("116.00")), status);
    }
}
