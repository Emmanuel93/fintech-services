package com.fintech.accounting;

import com.fintech.accounting.application.CloseReport.CloseCheck;
import com.fintech.accounting.application.LedgerViews.VoucherView;
import com.fintech.accounting.application.port.out.CloseReconciliationQueries;
import com.fintech.accounting.application.port.out.TreasuryCashBookRepository;
import com.fintech.accounting.application.service.CustomerBalancePostingService;
import com.fintech.accounting.application.service.LedgerQueryService;
import com.fintech.accounting.application.service.PayoutLinkProjection;
import com.fintech.accounting.application.service.PostingService;
import com.fintech.accounting.application.service.TreasuryPostingService;
import com.fintech.accounting.domain.PaymentOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clearing accounts at a month end, against a real database: collections in transit (1103), SPEI to settle (2103)
 * and the payers' balances in favor (2102), reconciled item by item.
 *
 * <p>The story is 30 November 2026, a Monday: a payment received at 19:00 is the portfolio's 30th and the bank's
 * 1 December (after the 18:00 cutoff); a disposition ordered at 18:30 goes out on the 1st as well. November closes
 * with both in transit, listed; December clears them. Then each way an item can fail to explain itself blocks the
 * close, one at a time.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.balance-updated", "accounting.journal-entry-created", "accounting.invoice-requested",
        "accounting.reconciliation-alert"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class ClearingAccountsCloseIT {

    private static final Logger log = LoggerFactory.getLogger(ClearingAccountsCloseIT.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static final String NOV = "202611", DEC = "202612";

    @Autowired PostingService posting;
    @Autowired CustomerBalancePostingService balances;
    @Autowired TreasuryPostingService bankSide;
    @Autowired PayoutLinkProjection payouts;
    @Autowired TreasuryCashBookRepository cashBook;
    @Autowired CloseReconciliationQueries reconciliation;
    @Autowired LedgerQueryService ledger;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final UUID loanA = UUID.randomUUID(), loanB = UUID.randomUUID();
    private final UUID borrower = UUID.randomUUID(), payer = UUID.randomUUID();
    /** The credit received at 19:00 on the 30th, and the order of the disposition made at 18:30. */
    private final UUID creditAtSeven = UUID.randomUUID(), orderB = UUID.randomUUID();
    private BigDecimal principalA = BigDecimal.ZERO;
    private long version = 1;

    @AfterEach
    void forgetEverything() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        jdbc.update("DELETE FROM accounting.journal_entries", none);
        jdbc.update("DELETE FROM accounting.vouchers", none);
        jdbc.update("DELETE FROM accounting.treasury_cash_book", none);
        jdbc.update("DELETE FROM accounting.customer_balance_movements", none);
        jdbc.update("DELETE FROM accounting.payout_links", none);
        jdbc.update("DELETE FROM accounting.commission_facts", none);
        jdbc.update("DELETE FROM accounting.account_balance_shadows", none);
    }

    @Test
    @DisplayName("30-nov: el pago de las 19:00 y la disposición de las 18:30 cierran noviembre en tránsito y diciembre los liquida")
    void novemberClosesWithItsItemsInTransit() {
        november();

        CloseCheck collections = check(NOV, "TRANSITO_COBRANZA_1103");
        assertThat(collections.ok()).as(collections.detail()).isTrue();
        assertThat(collections.items()).isEqualTo(1);
        assertThat(collections.amount()).isEqualByComparingTo("5000");
        assertThat(collections.detail()).contains("1 abonos por $5,000.00 en tránsito").contains("1 dic 2026");

        // The order of 18:30 is still in flight when November is checked.
        CloseCheck payoutsCheck = check(NOV, "TRANSITO_DISPERSION_2103");
        assertThat(payoutsCheck.ok()).as(payoutsCheck.detail()).isTrue();
        assertThat(payoutsCheck.items()).isEqualTo(1);
        assertThat(payoutsCheck.amount()).isEqualByComparingTo("7000");
        assertThat(payoutsCheck.detail()).contains("1 dispersiones por $7,000.00 por liquidar").contains("1 siguen en vuelo");

        CloseCheck favor = check(NOV, "SALDOS_A_FAVOR_2102");
        assertThat(favor.ok()).as(favor.detail()).isTrue();
        assertThat(favor.amount()).isEqualByComparingTo("2000");
        assertThat(favor.detail()).contains("1 clientes con saldo a favor");

        // Bancos only saw what the bank did in November: the order of the 2nd and the credit of the 20th.
        assertThat(balance("1101", NOV)).isEqualByComparingTo("-9200");   // −10,000 + 800
        assertThat(balance("1103", NOV)).isEqualByComparingTo("5000");
        assertThat(balance("2103", NOV)).isEqualByComparingTo("-7000");

        // December: the bank credits the 5,000 and reports the 7,000 out on the 1st; the 2,000 in favor pays the
        // installment due on the 2nd. Every item of November is closed.
        december();
        // November does not change: the order is still in its transit at the 30th, now with the date it went out.
        assertThat(check(NOV, "TRANSITO_DISPERSION_2103").detail()).contains("1 las liquida el banco el 1 dic 2026");
        for (String code : List.of("TRANSITO_COBRANZA_1103", "TRANSITO_DISPERSION_2103", "SALDOS_A_FAVOR_2102")) {
            CloseCheck c = check(DEC, code);
            assertThat(c.ok()).as(code + ": " + c.detail()).isTrue();
        }
        assertThat(check(DEC, "TRANSITO_COBRANZA_1103").detail()).contains("sin abonos en tránsito");
        assertThat(check(DEC, "TRANSITO_DISPERSION_2103").detail()).contains("sin dispersiones por liquidar");
        assertThat(check(DEC, "SALDOS_A_FAVOR_2102").detail()).contains("0 clientes con saldo a favor");
        assertThat(balance("1103", DEC)).isEqualByComparingTo("0");
        assertThat(balance("2103", DEC)).isEqualByComparingTo("0");
        assertThat(balance("2102", DEC)).isEqualByComparingTo("0");
        assertThat(balance("1101", DEC)).isEqualByComparingTo("-11200");   // −9,200 + 5,000 − 7,000
        // And Bancos says what treasury's cash book says, kind by kind.
        assertThat(check(DEC, "BANCOS_1101").ok()).as(check(DEC, "BANCOS_1101").detail()).isTrue();

        // Each loan shows the bank's side of its money: B the order that paid it out, A the credits that paid it.
        assertThat(ledger.vouchers(DEC, null, null, loanB, null, 0, 50).content())
                .extracting(VoucherView::triggerEvent).contains("TREASURY_STP_ORDER");
        assertThat(ledger.vouchers(NOV, null, null, loanA, null, 0, 50).content())
                .extracting(VoucherView::triggerEvent)
                .contains("DISPOSITION_THIRD_PARTY_CREDIT", "TREASURY_STP_ORDER", "PAYMENT_APPLIED", "TREASURY_SPEI_CREDIT");
        assertThat(ledger.vouchers(DEC, null, null, loanA, null, 0, 50).content())
                .extracting(VoucherView::triggerEvent).contains("TREASURY_SPEI_CREDIT", "PAYMENT_APPLIED")
                .doesNotContain("TREASURY_STP_ORDER");
    }

    @Test
    @DisplayName("un abono que el banco acreditó y cartera aplicó por otro importe bloquea el cierre")
    void aCreditAppliedForAnotherAmountBlocks() {
        november();
        UUID credit = UUID.randomUUID();
        pay("2026-11-27", "10:00", "900", credit);
        bankCredit(credit, "1000", "2026-11-27");

        CloseCheck c = check(NOV, "TRANSITO_COBRANZA_1103");
        assertThat(c.ok()).isFalse();
        assertThat(c.items()).isEqualTo(1);
        assertThat(c.amount()).isEqualByComparingTo("100");
        assertThat(c.detail()).contains("abono " + credit.toString().substring(0, 8) + ": cartera 900.00, banco 1,000.00");
    }

    @Test
    @DisplayName("un abono aplicado que el banco nunca reportó, o un cobro en tránsito sin abono, bloquean el cierre")
    void anUnreportedOrUnassignedCollectionBlocks() {
        november();
        UUID ghost = UUID.randomUUID();
        pay("2026-11-25", "11:00", "300", ghost);
        pay("2026-11-26", "11:00", "40", null);   // a share of a credit that does not say which

        CloseCheck c = check(NOV, "TRANSITO_COBRANZA_1103");
        assertThat(c.ok()).isFalse();
        assertThat(c.items()).isEqualTo(2);
        assertThat(c.detail()).contains("abono " + ghost.toString().substring(0, 8) + " aplicado por 300.00 que el banco no reporta")
                .contains("movimientos sin abono por 40.00");
    }

    @Test
    @DisplayName("una orden que el banco reportó sin crédito, o que paga de más, bloquea el cierre")
    void anOrderWithoutItsLoanOrBeyondItBlocks() {
        november();
        UUID stray = UUID.randomUUID();
        bankOrder(stray, "1500", "2026-11-24");                   // nobody said which loan it paid out
        UUID twice = UUID.randomUUID();
        payouts.onOrderAccepted(twice, loanA, "disp-A-bis", new BigDecimal("10000"));
        bankOrder(twice, "10000", "2026-11-03");                  // loan A's disposition, paid out a second time

        CloseCheck c = check(NOV, "TRANSITO_DISPERSION_2103");
        assertThat(c.ok()).isFalse();
        assertThat(c.items()).isEqualTo(2);
        assertThat(c.detail()).contains("orden " + stray.toString().substring(0, 8) + " sin crédito por 1,500.00")
                .contains("crédito " + loanA.toString().substring(0, 8) + ": dispuesto 10,000.00, liquidado 20,000.00");
    }

    @Test
    @DisplayName("un saldo a favor aplicado de más deja un cliente en negativo y bloquea el cierre")
    void aNegativeBalanceInFavorBlocks() {
        november();
        balances.onMovement(UUID.randomUUID(), UUID.randomUUID(), "APPLIED_TO_LOAN", new BigDecimal("-50"), null,
                UUID.randomUUID(), loanA, LocalDate.of(2026, 11, 28));

        CloseCheck c = check(NOV, "SALDOS_A_FAVOR_2102");
        assertThat(c.ok()).isFalse();
        assertThat(c.detail()).contains("1 clientes con saldo a favor negativo");
    }

    @Test
    @DisplayName("rendimiento: las tres cuentas puente de 20 mil abonos y 5 mil dispersiones se concilian en segundos")
    void performance_theClearingChecksOfAMonthRunInSeconds() {
        int credits = 20_000, orders = 5_000;
        List<MapSqlParameterSource> vouchers = new ArrayList<>(), entries = new ArrayList<>(), links = new ArrayList<>();
        for (int i = 0; i < credits; i++) {
            String credit = UUID.randomUUID().toString();
            BigDecimal amount = BigDecimal.valueOf(500 + i % 700);
            // The portfolio's share on the 30th; the bank's credit on the 30th for most, on 1 December for one in ten.
            String bankPeriod = i % 10 == 0 ? DEC : NOV;
            addLine(vouchers, entries, "perf-pay-" + i, "PAYMENT_APPLIED", credit, "1103", "1201", amount, NOV, null);
            addLine(vouchers, entries, "perf-bank-" + i, "TREASURY_SPEI_CREDIT", credit, "1101", "1103", amount, bankPeriod, null);
        }
        for (int i = 0; i < orders; i++) {
            UUID loan = UUID.randomUUID(), order = UUID.randomUUID();
            BigDecimal amount = BigDecimal.valueOf(5_000 + i);
            addLine(vouchers, entries, "perf-disp-" + i, "DISPOSITION_THIRD_PARTY_CREDIT", null, "1201", "2103", amount, NOV, loan);
            addLine(vouchers, entries, "perf-order-" + i, "TREASURY_STP_ORDER", order.toString(), "2103", "1101", amount,
                    i % 20 == 0 ? DEC : NOV, null);
            links.add(new MapSqlParameterSource("order", order).addValue("loan", loan));
        }
        jdbc.batchUpdate("""
                INSERT INTO accounting.vouchers (voucher_id, voucher_type, period, folio, voucher_date, concept,
                       source_event_id, trigger_event, credit_account_id, total_debit, total_credit, status,
                       is_late_posting, created_at, clearing_ref)
                VALUES (:id, 'DIARIO', :period, :folio, :at, 'rendimiento', :source, :trigger, :loan, :amount, :amount,
                        'POSTED', false, now(), :ref)""", vouchers.toArray(MapSqlParameterSource[]::new));
        jdbc.batchUpdate("""
                INSERT INTO accounting.journal_entries (source_event_id, trigger_event, credit_account_id, debit_account,
                       credit_account, amount, description, period, posting_date, voucher_id, line_no)
                VALUES (:source, :trigger, :loan, :debit, :credit, :amount, 'rendimiento', :period, :at, :id, 1)""",
                entries.toArray(MapSqlParameterSource[]::new));
        jdbc.batchUpdate("INSERT INTO accounting.payout_links (payment_request_id, credit_account_id) VALUES (:order, :loan)",
                links.toArray(MapSqlParameterSource[]::new));

        long start = System.nanoTime();
        List<CloseCheck> checks = reconciliation.reconcile(NOV, LocalDate.of(2026, 11, 30), at("2026-11-30", "00:00"), true);
        long millis = (System.nanoTime() - start) / 1_000_000;
        log.info("Cierre con {} abonos y {} dispersiones en las cuentas puente: {} ms", credits, orders, millis);

        CloseCheck collections = byCode(checks, "TRANSITO_COBRANZA_1103");
        CloseCheck payoutsCheck = byCode(checks, "TRANSITO_DISPERSION_2103");
        assertThat(collections.ok()).as(collections.detail()).isTrue();
        assertThat(collections.items()).isEqualTo(credits / 10);
        assertThat(payoutsCheck.ok()).as(payoutsCheck.detail()).isTrue();
        assertThat(payoutsCheck.items()).isEqualTo(orders / 20);
        assertThat(millis).as("ms para conciliar %d abonos y %d dispersiones", credits, orders).isLessThan(8_000L);
    }

    // ── The story ────────────────────────────────────────────────────────────────────────────────────────────

    /** November: two dispositions, three credits (one applied the same day, one after the cutoff, one returned). */
    private void november() {
        dispose(loanA, "10000", "2026-11-02", "10:00");
        UUID orderA = UUID.randomUUID();
        payouts.onOrderAccepted(orderA, loanA, "disp-A", new BigDecimal("10000"));
        bankOrder(orderA, "10000", "2026-11-02");

        // Ordered at 18:30 on the 30th, after the cutoff: the bank reports it on 1 December.
        dispose(loanB, "7000", "2026-11-30", "18:30");
        payouts.onOrderAccepted(orderB, loanB, "disp-B", new BigDecimal("7000"));

        // 800 received on the 20th at 10:00: the same day for the portfolio and the bank.
        UUID morning = UUID.randomUUID();
        pay("2026-11-20", "10:00", "800", morning);
        bankCredit(morning, "800", "2026-11-20");

        // 5,000 received at 19:00 on the 30th: 3,000 pay loan A, 2,000 stay in favor of the payer. Treasury records
        // it that same night with the SPEI operation date STP gave it, 1 December.
        pay("2026-11-30", "19:00", "3000", creditAtSeven);
        balances.onMovement(UUID.randomUUID(), payer, "EXCESS_FROM_CREDIT", new BigDecimal("2000"), creditAtSeven,
                null, null, LocalDate.of(2026, 11, 30));
        bankCredit(creditAtSeven, "5000", "2026-12-01");

        // A credit to a blocked CLABE: confirmed by no one, returned the same operation day.
        UUID returned = UUID.randomUUID();
        bankMovement("CLIENT_PAYMENT", "CREDIT", "CREDIT:" + returned, "450", "2026-11-25", "RET");
        bankMovement("PAYMENT_RETURN", "DEBIT", "CREDIT-RETURNED:" + returned, "450", "2026-11-25", "RET");
    }

    /** December: the bank reports the order of the 30th out, and the balance in favor pays an installment. */
    private void december() {
        bankOrder(orderB, "7000", "2026-12-01");
        UUID order = UUID.randomUUID();
        balances.onMovement(UUID.randomUUID(), payer, "APPLIED_TO_LOAN", new BigDecimal("-2000"), null, order, loanA,
                LocalDate.of(2026, 12, 2));
        posting.onBalanceUpdated("pay-balance-" + order, loanA, borrower, new BigDecimal("4200"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("4200"), "PAYMENT_APPLIED", ++version, "S_TEST", at("2026-12-02", "00:06"),
                new BigDecimal("2000"), new BigDecimal("-2000"), BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                new PaymentOrigin(PaymentOrigin.CREDIT_BALANCE, null));
    }

    private void dispose(UUID loan, String amount, String day, String time) {
        BigDecimal principal = new BigDecimal(amount);
        if (loan.equals(loanA)) principalA = principal;
        posting.onBalanceUpdated("disp-" + loan, loan, borrower, principal, BigDecimal.ZERO, BigDecimal.ZERO, principal,
                "DISPOSITION_THIRD_PARTY_CREDIT", ++version, "S_TEST", at(day, time));
    }

    /** A share of a received SPEI credit applied to loan A. */
    private void pay(String day, String time, String amount, UUID credit) {
        BigDecimal paid = new BigDecimal(amount);
        principalA = principalA.subtract(paid);
        posting.onBalanceUpdated("pay-" + UUID.randomUUID(), loanA, borrower, principalA, BigDecimal.ZERO, BigDecimal.ZERO,
                principalA, "PAYMENT_APPLIED", ++version, "S_TEST", at(day, time), paid, paid.negate(), BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, new PaymentOrigin(PaymentOrigin.SPEI_CREDIT, credit));
    }

    private void bankCredit(UUID credit, String amount, String operationDay) {
        bankMovement("CLIENT_PAYMENT", "CREDIT", "CREDIT:" + credit, amount, operationDay, "SPEI" + credit.toString().substring(0, 6));
    }

    private void bankOrder(UUID order, String amount, String operationDay) {
        bankMovement("STP_ORDER", "DEBIT", "settled-" + order, amount, operationDay, order.toString());
    }

    /** A movement of treasury's cash book, kept and given its bank side as the listener does. */
    private void bankMovement(String type, String direction, String fact, String amount, String day, String reference) {
        UUID id = UUID.randomUUID();
        cashBook.record(id, type, direction, new BigDecimal(amount), LocalDate.parse(day), reference, fact);
        bankSide.onCashMovement(id, type, fact, new BigDecimal(amount), LocalDate.parse(day), reference);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private CloseCheck check(String period, String code) {
        LocalDate last = LocalDate.parse(period.substring(0, 4) + "-" + period.substring(4) + "-01").plusMonths(1).minusDays(1);
        return byCode(reconciliation.reconcile(period, last, last.atStartOfDay(MX).toInstant(), true), code);
    }

    private static CloseCheck byCode(List<CloseCheck> checks, String code) {
        return checks.stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    /** The balance of an account through a period (debits − credits). */
    private BigDecimal balance(String code, String through) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN debit_account = :c THEN amount ELSE 0 END)
                              - SUM(CASE WHEN credit_account = :c THEN amount ELSE 0 END), 0)
                  FROM accounting.journal_entries WHERE period <= :p AND :c IN (debit_account, credit_account)""",
                new MapSqlParameterSource("c", code).addValue("p", through), BigDecimal.class);
    }

    private static Instant at(String day, String time) {
        return LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(MX).toInstant();
    }

    private static int folio = 0;

    private static void addLine(List<MapSqlParameterSource> vouchers, List<MapSqlParameterSource> entries, String source,
                                String trigger, String ref, String debit, String credit, BigDecimal amount,
                                String period, UUID loan) {
        UUID id = UUID.randomUUID();
        Timestamp when = Timestamp.from(at(NOV.equals(period) ? "2026-11-30" : "2026-12-01", "12:00"));
        vouchers.add(new MapSqlParameterSource("id", id).addValue("period", period).addValue("folio", ++folio)
                .addValue("at", when).addValue("source", source).addValue("trigger", trigger).addValue("loan", loan)
                .addValue("amount", amount).addValue("ref", ref));
        entries.add(new MapSqlParameterSource("id", id).addValue("source", source).addValue("trigger", trigger)
                .addValue("loan", loan).addValue("debit", debit).addValue("credit", credit).addValue("amount", amount)
                .addValue("period", period).addValue("at", when));
    }
}
