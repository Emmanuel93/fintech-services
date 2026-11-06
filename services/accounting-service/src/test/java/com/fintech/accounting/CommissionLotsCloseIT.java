package com.fintech.accounting;

import com.fintech.accounting.application.CloseReport.CloseCheck;
import com.fintech.accounting.application.CommissionMoves.Accrual;
import com.fintech.accounting.application.CommissionMoves.LotPayout;
import com.fintech.accounting.application.port.out.CloseReconciliationQueries;
import com.fintech.accounting.application.port.out.CommissionFacts;
import com.fintech.accounting.application.port.out.CommissionFacts.Fact;
import com.fintech.accounting.application.port.out.CommissionFacts.Kind;
import com.fintech.accounting.application.port.out.TreasuryCashBookRepository;
import com.fintech.accounting.application.service.CommissionPostingService;
import com.fintech.accounting.application.service.PayoutLinkProjection;
import com.fintech.accounting.application.service.TreasuryPostingService;
import com.fintech.accounting.domain.AccountCodes;
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
 * Commissions payable (2120) and their lots paid by the SPEI rail, at month ends, against a real database.
 *
 * <p>The story: in April a natural person who distributes the line accrues 600 + 96 of IVA and 400 + 64 that a returned
 * payment takes back whole; a company that places SME loans accrues 700 + 112. April closes owing 1,508.00 to two
 * beneficiaries for their products. On 5 May the lots' SPEI are requested: the person's withholds 60 of ISR and 64 of
 * IVA and pays 572.00; the company's pays 812.00, fails, and is requested again. The bank settles the person's on
 * 5 May and the company's second order on 1 June: May closes owing nothing, with 812.00 in flight in 2103, listed as the
 * company's lot; June clears it. Then each way 2120 can fail to explain itself blocks the close.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.balance-updated", "accounting.journal-entry-created", "accounting.invoice-requested",
        "accounting.reconciliation-alert"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class CommissionLotsCloseIT {

    private static final Logger log = LoggerFactory.getLogger(CommissionLotsCloseIT.class);

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
    private static final String APR = "202604", MAY = "202605", JUN = "202606";

    @Autowired CommissionPostingService commissions;
    @Autowired CommissionFacts facts;
    @Autowired PayoutLinkProjection payouts;
    @Autowired TreasuryPostingService bankSide;
    @Autowired TreasuryCashBookRepository cashBook;
    @Autowired CloseReconciliationQueries reconciliation;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final UUID person = UUID.randomUUID(), company = UUID.randomUUID();
    private final UUID personLot = UUID.randomUUID(), companyLot = UUID.randomUUID();
    private final UUID orderPerson = UUID.randomUUID(), orderCompany1 = UUID.randomUUID(), orderCompany2 = UUID.randomUUID();

    @AfterEach
    void forgetEverything() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        for (String table : List.of("journal_entries", "vouchers", "treasury_cash_book", "payout_links", "commission_facts")) {
            jdbc.update("DELETE FROM accounting." + table, none);
        }
    }

    @Test
    @DisplayName("April owes 1,508.00 to two beneficiaries; May owes nothing and has the company's lot in flight; "
            + "June clears it")
    void theLotsOfAMonth() {
        april();

        CloseCheck april = check(APR, "COMISIONES_2120");
        assertThat(april.ok()).as(april.detail()).isTrue();
        assertThat(april.detail()).contains("2 beneficiarias·producto con $1,508.00 por pagar");
        assertThat(balance(AccountCodes.COMISIONES_POR_PAGAR, APR)).isEqualByComparingTo("-1508.00");
        assertThat(balance(AccountCodes.IVA_PENDIENTE_ACREDITAR, APR)).isEqualByComparingTo("208.00");

        may();

        CloseCheck payable = check(MAY, "COMISIONES_2120");
        CloseCheck inFlight = check(MAY, "TRANSITO_DISPERSION_2103");
        assertThat(payable.ok()).as(payable.detail()).isTrue();
        assertThat(payable.detail()).isEqualTo("sin comisiones por pagar");
        assertThat(inFlight.ok()).as(inFlight.detail()).isTrue();
        assertThat(inFlight.detail()).contains("1 dispersiones por $812.00 por liquidar").contains("1 siguen en vuelo");
        assertThat(balance(AccountCodes.COMISIONES_POR_PAGAR, MAY)).isEqualByComparingTo("0");
        assertThat(balance(AccountCodes.ISR_RETENIDO_POR_ENTERAR, MAY)).isEqualByComparingTo("-60.00");
        assertThat(balance(AccountCodes.IVA_RETENIDO_POR_ENTERAR, MAY)).isEqualByComparingTo("-64.00");
        assertThat(balance(AccountCodes.IVA_ACREDITABLE_PAGADO, MAY)).isEqualByComparingTo("144.00");
        // What was withheld of the IVA stays pending until it is paid to the SAT on the beneficiary's behalf.
        assertThat(balance(AccountCodes.IVA_PENDIENTE_ACREDITAR, MAY)).isEqualByComparingTo("64.00");
        assertThat(balance(AccountCodes.BANCOS, MAY)).isEqualByComparingTo("-572.00");

        bankOrder(orderCompany2, "812.00", "2026-06-01");

        assertThat(check(MAY, "TRANSITO_DISPERSION_2103").detail()).contains("1 las liquida el banco el 1 jun 2026");
        CloseCheck june = check(JUN, "TRANSITO_DISPERSION_2103");
        assertThat(june.ok()).as(june.detail()).isTrue();
        assertThat(june.detail()).contains("sin dispersiones por liquidar");
        assertThat(balance(AccountCodes.SPEI_POR_LIQUIDAR, JUN)).isEqualByComparingTo("0");
        assertThat(balance(AccountCodes.BANCOS, JUN)).isEqualByComparingTo("-1384.00");
        assertThat(check(JUN, "COMISIONES_2120").ok()).isTrue();
    }

    @Test
    @DisplayName("a movement of 2120 that names no beneficiary blocks the close")
    void unattributedMovementBlocks() {
        april();
        voucher("stray-1", "COMMISSION_ACCRUED", null, AccountCodes.GASTO_COMISIONES, AccountCodes.COMISIONES_POR_PAGAR,
                "100.00", APR, "2026-04-20");

        CloseCheck c = check(APR, "COMISIONES_2120");

        assertThat(c.ok()).isFalse();
        assertThat(c.detail()).contains("$100.00 en 2120 sin beneficiaria");
    }

    @Test
    @DisplayName("a commission reported and never booked blocks the close, naming the beneficiary and the product")
    void factWithoutVoucherBlocks() {
        april();
        facts.record(new Fact(UUID.randomUUID().toString(), Kind.ACCRUED, person, "DISTRIBUTOR_LINE", null, null,
                new BigDecimal("116.00"), LocalDate.of(2026, 4, 28)));

        CloseCheck c = check(APR, "COMISIONES_2120");

        assertThat(c.ok()).isFalse();
        assertThat(c.detail()).contains("beneficiaria " + person.toString().substring(0, 8) + " · DISTRIBUTOR_LINE")
                .contains("comisiones 812.00, mayor 696.00");
    }

    @Test
    @DisplayName("the bank settling a lot's order for more than its net blocks the close, naming the lot")
    void lotOverpaidBlocks() {
        april();
        LotPayout lot = personLot(1);
        commissions.onLotPayoutRequested(lot);
        payouts.onItemOrderAccepted(orderPerson, lot.itemRef(), lot.itemRef(), lot.net());
        bankOrder(orderPerson, "600.00", "2026-05-05");

        CloseCheck c = check(MAY, "TRANSITO_DISPERSION_2103");

        assertThat(c.ok()).isFalse();
        assertThat(c.detail()).contains("lote " + personLot.toString().substring(0, 8) + ": ordenado 572.00, liquidado 600.00");
    }

    @Test
    @DisplayName("performance: 20,000 commissions of 1,000 beneficiaries·product and their 1,000 lots reconciled in under "
            + "8 seconds")
    void performance() {
        List<MapSqlParameterSource> vouchers = new ArrayList<>(), entries = new ArrayList<>(), rows = new ArrayList<>();
        for (int b = 0; b < 1_000; b++) {
            UUID beneficiary = UUID.randomUUID();
            String product = b % 2 == 0 ? "DISTRIBUTOR_LINE" : "SME_LOAN";
            String key = "COM:" + beneficiary + ":" + product;
            for (int i = 0; i < 20; i++) {
                String id = "perf-acc-" + b + "-" + i;
                line(vouchers, entries, id, "COMMISSION_ACCRUED", key, "5104", "2120", new BigDecimal("116.00"), APR);
                rows.add(fact(id, "ACCRUED", beneficiary, product, null, new BigDecimal("116.00"), "2026-04-15"));
            }
            UUID lot = UUID.randomUUID();
            line(vouchers, entries, "perf-lot-" + b, "COMMISSION_LOT_PAYOUT", "LOTE:" + lot, "2120", "2103",
                    new BigDecimal("2320.00"), MAY);
            rows.add(fact("perf-lot-" + b, "LOT_SENT", beneficiary, product, lot, new BigDecimal("-2320.00"), "2026-05-05"));
        }
        insert(vouchers, entries, rows);

        long start = System.nanoTime();
        CloseCheck april = check(APR, "COMISIONES_2120");
        CloseCheck may = check(MAY, "COMISIONES_2120");
        long millis = (System.nanoTime() - start) / 1_000_000;

        log.info("2120 con 20000 comisiones y 1000 lotes conciliado (dos cierres) en {} ms", millis);
        assertThat(april.ok()).as(april.detail()).isTrue();
        assertThat(april.detail()).contains("1000 beneficiarias·producto con $2,320,000.00 por pagar");
        assertThat(may.ok()).as(may.detail()).isTrue();
        assertThat(millis).isLessThan(8_000L);
    }

    // ── The story ─────────────────────────────────────────────────────────────────────────────────────────

    private void april() {
        UUID first = UUID.randomUUID(), returned = UUID.randomUUID();
        commissions.onCommissionAccrued(accrual(first, person, "DISTRIBUTOR_LINE", "600.00", "96.00", "2026-04-10"));
        commissions.onCommissionAccrued(accrual(returned, person, "DISTRIBUTOR_LINE", "400.00", "64.00", "2026-04-14"));
        commissions.onCommissionReversed(new Accrual(UUID.randomUUID(), null, person, "DISTRIBUTOR_LINE",
                new BigDecimal("400.00"), new BigDecimal("64.00"), null, returned, noon("2026-04-16")));
        commissions.onCommissionAccrued(accrual(UUID.randomUUID(), company, "SME_LOAN", "700.00", "112.00", "2026-04-22"));
    }

    private void may() {
        LotPayout ofPerson = personLot(1);
        commissions.onLotPayoutRequested(ofPerson);
        payouts.onItemOrderAccepted(orderPerson, ofPerson.itemRef(), ofPerson.itemRef(), ofPerson.net());
        bankOrder(orderPerson, "572.00", "2026-05-05");

        LotPayout first = companyLot(1), second = companyLot(2);
        commissions.onLotPayoutRequested(first);
        payouts.onItemOrderAccepted(orderCompany1, first.itemRef(), first.itemRef(), first.net());
        commissions.onLotPayoutFailed(first);
        commissions.onLotPayoutRequested(second);
        payouts.onItemOrderAccepted(orderCompany2, second.itemRef(), second.itemRef(), second.net());
    }

    private LotPayout personLot(int attempt) {
        return new LotPayout(personLot, attempt, person, "DISTRIBUTOR_LINE", new BigDecimal("600.00"),
                new BigDecimal("96.00"), new BigDecimal("60.00"), new BigDecimal("64.00"), new BigDecimal("696.00"),
                new BigDecimal("572.00"), noon("2026-05-05"));
    }

    private LotPayout companyLot(int attempt) {
        return new LotPayout(companyLot, attempt, company, "SME_LOAN", new BigDecimal("700.00"), new BigDecimal("112.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("812.00"), new BigDecimal("812.00"), noon("2026-05-05"));
    }

    private static Accrual accrual(UUID id, UUID beneficiary, String product, String amount, String iva, String day) {
        return new Accrual(id, UUID.randomUUID(), beneficiary, product, new BigDecimal(amount), new BigDecimal(iva),
                "DISTRIBUTOR_INTEREST_SHARE", null, noon(day));
    }

    /** The bank reports the order out: treasury's cash book records it and gives it its bank side, as the listener does. */
    private void bankOrder(UUID order, String amount, String day) {
        UUID id = UUID.randomUUID();
        String fact = "settled-" + order;
        cashBook.record(id, "STP_ORDER", "DEBIT", new BigDecimal(amount), LocalDate.parse(day), order.toString(), fact);
        bankSide.onCashMovement(id, "STP_ORDER", fact, new BigDecimal(amount), LocalDate.parse(day), order.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────────────

    private CloseCheck check(String period, String code) {
        LocalDate last = LocalDate.parse(period.substring(0, 4) + "-" + period.substring(4) + "-01").plusMonths(1).minusDays(1);
        return reconciliation.reconcile(period, last, last.atStartOfDay(MX).toInstant(), true).stream()
                .filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    /** The balance of an account through a period (debits − credits). */
    private BigDecimal balance(String code, String through) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN debit_account = :c THEN amount ELSE 0 END)
                              - SUM(CASE WHEN credit_account = :c THEN amount ELSE 0 END), 0)
                  FROM accounting.journal_entries WHERE period <= :p AND :c IN (debit_account, credit_account)""",
                new MapSqlParameterSource("c", code).addValue("p", through), BigDecimal.class);
    }

    private static Instant noon(String day) {
        return LocalDate.parse(day).atTime(LocalTime.NOON).atZone(MX).toInstant();
    }

    private static int folio = 0;

    private void voucher(String source, String trigger, String ref, String debit, String credit, String amount,
                         String period, String day) {
        List<MapSqlParameterSource> v = new ArrayList<>(), e = new ArrayList<>();
        line(v, e, source, trigger, ref, debit, credit, new BigDecimal(amount), period, day);
        insert(v, e, List.of());
    }

    private static void line(List<MapSqlParameterSource> vouchers, List<MapSqlParameterSource> entries, String source,
                             String trigger, String ref, String debit, String credit, BigDecimal amount, String period) {
        line(vouchers, entries, source, trigger, ref, debit, credit, amount, period,
                period.substring(0, 4) + "-" + period.substring(4) + "-15");
    }

    private static void line(List<MapSqlParameterSource> vouchers, List<MapSqlParameterSource> entries, String source,
                             String trigger, String ref, String debit, String credit, BigDecimal amount, String period,
                             String day) {
        UUID id = UUID.randomUUID();
        Timestamp when = Timestamp.from(noon(day));
        vouchers.add(new MapSqlParameterSource("id", id).addValue("period", period).addValue("folio", ++folio)
                .addValue("at", when).addValue("source", source).addValue("trigger", trigger)
                .addValue("amount", amount).addValue("ref", ref));
        entries.add(new MapSqlParameterSource("id", id).addValue("source", source).addValue("trigger", trigger)
                .addValue("debit", debit).addValue("credit", credit).addValue("amount", amount)
                .addValue("period", period).addValue("at", when));
    }

    private static MapSqlParameterSource fact(String id, String kind, UUID beneficiary, String product, UUID batch,
                                              BigDecimal gross, String day) {
        return new MapSqlParameterSource("id", id).addValue("kind", kind).addValue("beneficiary", beneficiary)
                .addValue("product", product).addValue("batch", batch).addValue("gross", gross)
                .addValue("day", LocalDate.parse(day)).addValue("period", day.substring(0, 4) + day.substring(5, 7));
    }

    private void insert(List<MapSqlParameterSource> vouchers, List<MapSqlParameterSource> entries,
                        List<MapSqlParameterSource> rows) {
        jdbc.batchUpdate("""
                INSERT INTO accounting.vouchers (voucher_id, voucher_type, period, folio, voucher_date, concept,
                       source_event_id, trigger_event, credit_account_id, total_debit, total_credit, status,
                       is_late_posting, created_at, clearing_ref)
                VALUES (:id, 'DIARIO', :period, :folio, :at, 'prueba', :source, :trigger, NULL, :amount, :amount,
                        'POSTED', false, now(), :ref)""", vouchers.toArray(MapSqlParameterSource[]::new));
        jdbc.batchUpdate("""
                INSERT INTO accounting.journal_entries (source_event_id, trigger_event, credit_account_id, debit_account,
                       credit_account, amount, description, period, posting_date, voucher_id, line_no)
                VALUES (:source, :trigger, NULL, :debit, :credit, :amount, 'prueba', :period, :at, :id, 1)""",
                entries.toArray(MapSqlParameterSource[]::new));
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO accounting.commission_facts (fact_id, kind, beneficiary_party_id, product_type, batch_id,
                           gross, business_date, period)
                    VALUES (:id, :kind, :beneficiary, :product, :batch, :gross, :day, :period)""",
                    rows.toArray(MapSqlParameterSource[]::new));
        }
    }
}
