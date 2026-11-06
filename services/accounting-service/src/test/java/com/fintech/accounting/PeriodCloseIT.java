package com.fintech.accounting;

import com.fintech.accounting.application.CloseReport;
import com.fintech.accounting.application.CloseReport.CloseCheck;
import com.fintech.accounting.application.port.out.AccountingPeriodRepository;
import com.fintech.accounting.application.port.out.CloseReconciliationQueries;
import com.fintech.accounting.application.port.out.TreasuryCashBookRepository;
import com.fintech.accounting.application.service.FundingPostingService;
import com.fintech.accounting.application.service.FundingPostingService.FundingFact;
import com.fintech.accounting.application.service.PeriodCloseService;
import com.fintech.accounting.application.service.PayoutLinkProjection;
import com.fintech.accounting.application.service.PeriodNotReconciledException;
import com.fintech.accounting.application.service.PostingService;
import com.fintech.accounting.application.service.ProvisionPostingService;
import com.fintech.accounting.application.service.TreasuryPostingService;
import com.fintech.accounting.application.service.YearEndCloseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The month-end close against a real database: the reconciliation's SQL over the ledger, the shadows, the reserves
 * and the portfolio's day closes.
 *
 * <p>One month (August 2026) of one loan goes from "cannot close" to closed: the portfolio has not closed the last day
 * yet, then risk has not measured the reserve at that date, then everything is there and the month closes with its
 * trial balance. A posting error afterwards (an entry to 1201 the portfolio never had) shows up account by account.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.balance-updated", "credit-portfolio.day-closed", "credit-portfolio.account-day-closed",
        "risk.assessment-updated", "accounting.journal-entry-created", "accounting.invoice-requested",
        "accounting.reconciliation-alert"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class PeriodCloseIT {

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
    private static final String AUG = "202608";

    @Autowired PostingService posting;
    @Autowired ProvisionPostingService provisions;
    @Autowired FundingPostingService funding;
    @Autowired TreasuryCashBookRepository cashBook;
    @Autowired TreasuryPostingService bankSide;
    @Autowired PayoutLinkProjection payouts;
    @Autowired PeriodCloseService closes;
    @Autowired YearEndCloseService yearEnd;
    @Autowired CloseReconciliationQueries reconciliation;
    @Autowired AccountingPeriodRepository periods;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, Object> kafka;

    private static Instant at(String day, String time) {
        return LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(MX).toInstant();
    }

    private void fund(UUID source, String kind, String type, String amount, String day, String time,
                      String contributed, String principal, String interest, String accruedFrom, String accruedThrough) {
        funding.onMovement(new FundingFact("FUNDING:" + UUID.randomUUID(), UUID.randomUUID(), source, kind,
                "CAPITAL".equals(kind) ? "Accionistas fundadores" : "Banco de Fomento de Ejemplo", "DOC-1", type,
                new BigDecimal(amount), LocalDate.parse(day), accruedFrom == null ? null : LocalDate.parse(accruedFrom),
                accruedThrough == null ? null : LocalDate.parse(accruedThrough), new BigDecimal(contributed),
                new BigDecimal(principal), new BigDecimal(interest), "ACTIVE", 36, at(day, time)));
    }

    /** A movement of treasury's cash book, as banking announces it. */
    private void cash(String type, String direction, String amount, String day, String source) {
        cash(type, direction, amount, day, source, "REF");
    }

    /** A movement of treasury's cash book, kept and given its bank side as the listener does. */
    private void cash(String type, String direction, String amount, String day, String source, String reference) {
        UUID id = UUID.randomUUID();
        String fact = source == null ? "SRC:" + UUID.randomUUID() : source;
        cashBook.record(id, type, direction, new BigDecimal(amount), LocalDate.parse(day), reference, fact);
        bankSide.onCashMovement(id, type, fact, new BigDecimal(amount), LocalDate.parse(day), reference);
    }

    @Test
    void aMonthClosesOnlyOnceItReconciles() {
        UUID account = UUID.randomUUID(), party = UUID.randomUUID();
        // Where the lent money comes from: capital paid in on the 1st, a funding line drawn on the 2nd.
        UUID equity = UUID.randomUUID(), line = UUID.randomUUID();
        fund(equity, "CAPITAL", "CONTRIBUTION", "50000", "2026-08-01", "12:00", "50000", "0", "0", null, null);
        fund(line, "CREDIT_LINE", "DRAWDOWN", "20000", "2026-08-02", "12:00", "0", "20000", "0", null, null);
        // Its opening fee: a transaction cost deducted from the liability and amortized over 36 months.
        fund(line, "CREDIT_LINE", "OPENING_FEE", "360", "2026-08-02", "12:00", "0", "20000", "0", null, null);
        posting.onBalanceUpdated("evt-disp-" + account, account, party, new BigDecimal("10000"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("10000"), "DISPOSITION_THIRD_PARTY_CREDIT", 1, "S_TEST",
                at("2026-08-05", "12:00"));
        posting.onBalanceUpdated("evt-int-" + account, account, party, new BigDecimal("10000"), new BigDecimal("100"),
                BigDecimal.ZERO, new BigDecimal("10100"), "CHARGE_ORDINARY_INTEREST", 2, "S_TEST",
                at("2026-08-20", "23:59:59"));

        // Treasury's cash book has the funding, but not yet the STP order that takes the 10,000 out: the disposition
        // waits in SPEI to settle (2103) until the bank reports it, which is right at any month end.
        cash("FUNDING", "CREDIT", "50000", "2026-08-01", null);
        cash("FUNDING", "CREDIT", "20000", "2026-08-02", null);
        cash("FUNDING", "DEBIT", "360", "2026-08-02", null);
        // A payment the portfolio rejected: it came in and went back, and never reached the books.
        cash("CLIENT_PAYMENT", "CREDIT", "500", "2026-08-10", "PAYMENT:p-1");
        cash("PAYMENT_RETURN", "DEBIT", "500", "2026-08-10", "PAYMENT-REJECTED:p-1");

        // The portfolio has not closed the 31st: the month cannot close.
        CloseReport before = closes.reconciliation(AUG);
        assertThat(before.preview()).isTrue();
        assertThat(failed(before)).contains("CARTERA_CERRADA", "RESERVA_MEDIDA", "FONDEO_DEVENGADO")
                .doesNotContain("BANCOS_1101", "TRANSITO_DISPERSION_2103");
        assertThat(check(before, "TRANSITO_DISPERSION_2103").detail())
                .contains("1 dispersiones por $10,000.00 por liquidar").contains("1 siguen en vuelo");
        // Disbursement says which loan the order pays out; the bank reports it out on the 5th: 2103 clears.
        UUID order = UUID.randomUUID();
        payouts.onOrderAccepted(order, account, "disp-1", new BigDecimal("10000"));
        cash("STP_ORDER", "DEBIT", "10000", "2026-08-05", "settled-" + order, order.toString());
        CloseReport settled = closes.reconciliation(AUG);
        assertThat(failed(settled)).doesNotContain("BANCOS_1101", "TRANSITO_DISPERSION_2103");
        assertThat(check(settled, "TRANSITO_DISPERSION_2103").detail()).contains("sin dispersiones por liquidar");

        // The portfolio closes the 31st (through Kafka, as it publishes it); risk has not measured the reserve yet.
        kafka.send("credit-portfolio.day-closed", "2026-08-31", Map.of("businessDate", "2026-08-31",
                "delinquencyAsOf", "2026-09-01", "accounts", 1, "errors", 0, "heldAccounts", List.of()));
        await().atMost(20, TimeUnit.SECONDS).until(() -> !failed(closes.reconciliation(AUG)).contains("CARTERA_CERRADA"));
        assertThatThrownBy(() -> closes.close(AUG))
                .isInstanceOf(PeriodNotReconciledException.class)
                .hasMessageContaining("Reserva IFRS 9 medida a la fecha del cierre");
        assertThat(periods.findById(AUG)).hasValueSatisfying(p -> assertThat(p.isOpen()).isTrue());

        // Risk measures the reserve at the end of the 31st, treasury accrues the line through it (30 days of 20,000
        // at 13 % / 360 = 216.67): the month reconciles and closes.
        provisions.onRiskAssessment(account, party, new BigDecimal("350"), at("2026-08-31", "23:59:59"),
                "STAGE_1", new BigDecimal("10100"));
        fund(line, "CREDIT_LINE", "INTEREST_ACCRUAL", "216.67", "2026-08-31", "23:59:59", "0", "20000", "216.67",
                "2026-08-02", "2026-08-31");
        CloseReport closed = closes.close(AUG);

        assertThat(closed.reconciled()).isTrue();
        assertThat(closed.closedAt()).isNotNull();
        assertThat(periods.findById(AUG)).hasValueSatisfying(p -> assertThat(p.isOpen()).isFalse());
        assertThat(check(closed, "CAPITAL_1201").detail()).contains("$10,000.00");
        assertThat(check(closed, "RESERVA_1290").ok()).isTrue();
        assertThat(check(closed, "FONDEO_MAYOR").detail()).contains("$50,000.00").contains("$20,000.00").contains("$216.67");
        // 50,000 of capital + 20,000 drawn − 360 of fee − 10,000 lent: the bank never ran negative.
        assertThat(check(closed, "BANCOS_NO_NEGATIVO").detail()).contains("$59,640.00");
        // And treasury says the same, by business day: 1101 reconciles against the cash book.
        assertThat(check(closed, "BANCOS_1101").ok()).isTrue();
        assertThat(check(closed, "BANCOS_1101").detail()).contains("$59,640.00").contains("1 pagos rechazados");
        // The trial balance is shown balanced, and the accounting equation holds.
        assertThat(check(closed, "BALANZA_CUADRA").ok()).isTrue();
        assertThat(check(closed, "ECUACION_CONTABLE").ok()).isTrue();
        // The close amortized August's share of the fee (360 / 36 = 10): 350 left, in the ledger and the calendar.
        assertThat(check(closed, "COSTOS_FONDEO_2209").ok()).isTrue();
        assertThat(check(closed, "COSTOS_FONDEO_2209").detail()).contains("$350.00");
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT outcome, attempts, trial_balance::text AS tb FROM accounting.period_closes WHERE period = :p",
                new MapSqlParameterSource("p", AUG));
        assertThat(stored.get("outcome")).isEqualTo("RECONCILED");
        assertThat(((Number) stored.get("attempts")).intValue()).isEqualTo(2);
        assertThat((String) stored.get("tb")).contains("\"accountCode\": \"1201\"");

        // Closing again returns the close made: same report, nothing booked twice.
        assertThat(closes.close(AUG).runAt()).isEqualTo(closed.runAt());

        // A posting error later — an entry to 1201 the portfolio never had — is found account by account.
        jdbc.update("""
                INSERT INTO accounting.journal_entries (source_event_id, trigger_event, credit_account_id, obligor_party_id,
                       debit_account, credit_account, amount, description, period)
                VALUES ('evt-stray', 'MANUAL', :account, :party, '1201', '1101', 25.50, 'error de posteo', '202609')""",
                new MapSqlParameterSource("account", account).addValue("party", party));
        try {
            CloseCheck capital = check(closes.reconciliation("202609"), "CAPITAL_1201");
            assertThat(capital.ok()).isFalse();
            assertThat(capital.items()).isEqualTo(1);
            assertThat(capital.amount()).isEqualByComparingTo("25.50");
            assertThat(capital.detail()).contains(account.toString());
        } finally {
            jdbc.update("DELETE FROM accounting.journal_entries WHERE source_event_id = 'evt-stray'",
                    new MapSqlParameterSource());
        }
    }

    @Test
    void theYearEndCloseTakesTheYearsResultToRetainedEarnings() {
        // A year (2025) of two units: revenue and an expense in one, revenue in the other, and a disbursement that is
        // not a result at all.
        UUID a = UUID.randomUUID(), party = UUID.randomUUID();
        List<MapSqlParameterSource> entries = List.of(
                entry(a, party, "ye-1", "1203", "4101", new BigDecimal("1000")).addValue("unit", "S_UNO").addValue("p", "202503"),
                entry(a, party, "ye-2", "5101", "1290", new BigDecimal("300")).addValue("unit", "S_UNO").addValue("p", "202511"),
                entry(a, party, "ye-3", "1203", "4103", new BigDecimal("200")).addValue("unit", "S_DOS").addValue("p", "202512"),
                entry(a, party, "ye-4", "1201", "1101", new BigDecimal("5000")).addValue("unit", "S_UNO").addValue("p", "202502"));
        jdbc.batchUpdate("""
                INSERT INTO accounting.journal_entries (source_event_id, trigger_event, credit_account_id, obligor_party_id,
                       debit_account, credit_account, amount, description, period, org_unit_code)
                VALUES (:source, 'YE_TEST', :a, :party, :debit, :credit, :amount, 'ejercicio', :p, :unit)""",
                entries.toArray(MapSqlParameterSource[]::new));
        try {
            CloseCheck check = yearEnd.close(2025, MX);
            yearEnd.close(2025, MX);   // twice: nothing booked twice

            assertThat(check.amount()).isEqualByComparingTo("900");   // 1,000 + 200 − 300
            assertThat(check.items()).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM accounting.vouchers WHERE trigger_event = 'YEAR_END_CLOSE' AND period = '202513'""",
                    new MapSqlParameterSource(), Integer.class)).isEqualTo(2);
            // From January on, revenue and expenses start from zero and the result sits in 3201.
            assertThat(balance("4101", "202601")).isEqualByComparingTo("0");
            assertThat(balance("4103", "202601")).isEqualByComparingTo("0");
            assertThat(balance("5101", "202601")).isEqualByComparingTo("0");
            assertThat(balance("3201", "202601")).isEqualByComparingTo("-900");
            // December keeps the year's figures: the closing voucher is in period 13.
            assertThat(balance("4101", "202512")).isEqualByComparingTo("-1000");
            assertThat(balance("3201", "202512")).isEqualByComparingTo("0");
            // The books still balance, and the equation holds with the result in equity.
            List<CloseCheck> january = reconciliation.reconcile("202601", LocalDate.of(2026, 1, 31),
                    at("2026-01-31", "00:00"), true);
            for (String code : List.of("BALANZA_CUADRA", "ECUACION_CONTABLE")) {
                assertThat(january.stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().ok())
                        .as(code).isTrue();
            }
        } finally {
            MapSqlParameterSource none = new MapSqlParameterSource();
            jdbc.update("DELETE FROM accounting.journal_entries WHERE trigger_event IN ('YE_TEST', 'YEAR_END_CLOSE')", none);
            jdbc.update("DELETE FROM accounting.vouchers WHERE trigger_event = 'YEAR_END_CLOSE'", none);
        }
    }

    /** The balance of an account through a period (debits − credits). */
    private BigDecimal balance(String code, String through) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN debit_account = :c THEN amount ELSE 0 END)
                              - SUM(CASE WHEN credit_account = :c THEN amount ELSE 0 END), 0)
                  FROM accounting.journal_entries WHERE period <= :p AND :c IN (debit_account, credit_account)""",
                new MapSqlParameterSource("c", code).addValue("p", through), BigDecimal.class);
    }

    @Test
    void performance_theReconciliationOfFiveThousandAccountsRunsInSeconds() {
        // Five thousand loans of a later month, each with its principal, interest and reserve, consistent with their
        // shadows: the shape of a month of a real portfolio.
        int n = 5_000;
        List<MapSqlParameterSource> shadows = new ArrayList<>(), entries = new ArrayList<>(), reserves = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID a = UUID.randomUUID(), party = UUID.randomUUID();
            ids.add(a);
            BigDecimal principal = BigDecimal.valueOf(5_000 + i), interest = BigDecimal.valueOf(40 + i % 50);
            shadows.add(new MapSqlParameterSource("a", a).addValue("party", party).addValue("p", principal)
                    .addValue("t", principal.add(interest)));
            entries.add(entry(a, party, "perf-d-" + a, "1201", "1101", principal));
            entries.add(entry(a, party, "perf-i-" + a, "1203", "4101", interest));
            entries.add(entry(a, party, "perf-r-" + a, "5101", "1290", BigDecimal.valueOf(100)));
            reserves.add(new MapSqlParameterSource("a", a));
        }
        jdbc.batchUpdate("""
                INSERT INTO accounting.account_balance_shadows (credit_account_id, obligor_party_id, principal_balance,
                       total_debt, balance_version) VALUES (:a, :party, :p, :t, 2)""",
                shadows.toArray(MapSqlParameterSource[]::new));
        jdbc.batchUpdate("""
                INSERT INTO accounting.journal_entries (source_event_id, trigger_event, credit_account_id, obligor_party_id,
                       debit_account, credit_account, amount, description, period)
                VALUES (:source, 'PERF', :a, :party, :debit, :credit, :amount, 'rendimiento', '202612')""",
                entries.toArray(MapSqlParameterSource[]::new));
        jdbc.batchUpdate("""
                INSERT INTO accounting.provision_ledger (credit_account_id, last_booked_provision, measured_at)
                VALUES (:a, 100, '2026-12-31T23:59:59-06:00')""", reserves.toArray(MapSqlParameterSource[]::new));
        try {
            long start = System.nanoTime();
            List<CloseCheck> checks = reconciliation.reconcile("202612", LocalDate.of(2026, 12, 31),
                    at("2026-12-31", "00:00"), true);
            long millis = (System.nanoTime() - start) / 1_000_000;

            for (String code : List.of("CAPITAL_1201", "POR_COBRAR_1203", "RESERVA_1290")) {
                assertThat(checks.stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().ok())
                        .as(code).isTrue();
            }
            assertThat(millis).as("ms para conciliar %d créditos", n).isLessThan(5_000L);
        } finally {
            MapSqlParameterSource byId = new MapSqlParameterSource("ids", ids);
            jdbc.update("DELETE FROM accounting.journal_entries WHERE trigger_event = 'PERF'", byId);
            jdbc.update("DELETE FROM accounting.account_balance_shadows WHERE credit_account_id IN (:ids)", byId);
            jdbc.update("DELETE FROM accounting.provision_ledger WHERE credit_account_id IN (:ids)", byId);
        }
    }

    private static MapSqlParameterSource entry(UUID a, UUID party, String source, String debit, String credit,
                                               BigDecimal amount) {
        return new MapSqlParameterSource("a", a).addValue("party", party).addValue("source", source)
                .addValue("debit", debit).addValue("credit", credit).addValue("amount", amount);
    }

    private static List<String> failed(CloseReport r) {
        return r.checks().stream().filter(c -> c.blocking() && !c.ok()).map(CloseCheck::code).toList();
    }

    private static CloseCheck check(CloseReport r, String code) {
        return r.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }
}
