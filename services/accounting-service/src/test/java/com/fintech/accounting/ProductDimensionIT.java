package com.fintech.accounting;

import com.fintech.accounting.application.AccountActivation;
import com.fintech.accounting.application.CloseReport.CloseCheck;
import com.fintech.accounting.application.CommissionMoves.Accrual;
import com.fintech.accounting.application.CommissionMoves.LotPayout;
import com.fintech.accounting.application.LedgerViews.DimensionBalanceView;
import com.fintech.accounting.application.LedgerViews.ProductMovementView;
import com.fintech.accounting.application.LedgerViews.ProductSchemeView;
import com.fintech.accounting.application.LedgerViews.SchemeConceptView;
import com.fintech.accounting.application.LedgerViews.TrialBalanceRowView;
import com.fintech.accounting.application.port.out.CloseReconciliationQueries;
import com.fintech.accounting.application.port.out.TreasuryCashBookRepository;
import com.fintech.accounting.application.service.CommissionPostingService;
import com.fintech.accounting.application.service.LedgerQueryService;
import com.fintech.accounting.application.service.PayoutLinkProjection;
import com.fintech.accounting.application.service.PostingService;
import com.fintech.accounting.application.service.ProductAttributionService;
import com.fintech.accounting.application.service.TreasuryPostingService;
import com.fintech.accounting.application.service.VoucherPostingService;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.application.service.YearEndCloseService;
import com.fintech.accounting.domain.AccountCodes;
import com.fintech.accounting.infrastructure.adapter.out.persistence.LedgerQueryDao;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The product dimension against a real database.
 *
 * <p>The story, in May 2026: a personal loan is activated and disbursed (10,000) and the bank reports its order, whose
 * link to the loan was already known; a distributor's line is authorized (20,000) and accrues a commission of 600 + 96
 * of IVA, whose lot is paid by SPEI (572 net, 60 of ISR and 64 of IVA withheld) and reported by the bank before
 * disbursement's acceptance links the order to the lot; the institution draws 50,000 of funding. Every voucher carries
 * its product — its loan's, its item's, or none for the institution's — the trial balance splits by product without
 * losing a centavo, and the bank side booked before its link is attributed by the close's sweep.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.balance-updated", "accounting.journal-entry-created", "accounting.invoice-requested",
        "accounting.reconciliation-alert"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class ProductDimensionIT {

    private static final Logger log = LoggerFactory.getLogger(ProductDimensionIT.class);

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
    private static final String MAY = "202605";
    private static final String PERSONAL = "PERSONAL_LOAN", LINE = "DISTRIBUTOR_LINE";

    @Autowired PostingService posting;
    @Autowired CommissionPostingService commissions;
    @Autowired VoucherPostingService ledger;
    @Autowired PayoutLinkProjection payouts;
    @Autowired TreasuryPostingService bankSide;
    @Autowired TreasuryCashBookRepository cashBook;
    @Autowired ProductAttributionService attribution;
    @Autowired LedgerQueryService queries;
    @Autowired LedgerQueryDao ledgerQueries;
    @Autowired CloseReconciliationQueries reconciliation;
    @Autowired YearEndCloseService yearEnd;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final UUID personalLoan = UUID.randomUUID(), line = UUID.randomUUID(), distributor = UUID.randomUUID();
    private final UUID personalOrder = UUID.randomUUID(), lotOrder = UUID.randomUUID(), lot = UUID.randomUUID();

    @AfterEach
    void forgetEverything() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        for (String table : List.of("journal_entries", "vouchers", "treasury_cash_book", "payout_links",
                "commission_facts", "account_balance_shadows", "period_closes")) {
            jdbc.update("DELETE FROM accounting." + table, none);
        }
        jdbc.update("UPDATE accounting.accounting_periods SET status = 'OPEN', closed_at = NULL", none);
    }

    @Test
    @DisplayName("every voucher carries its product, and the trial balance by product adds up to the whole")
    void theDimension() {
        may();

        // The lot's bank side was reported before its link: the institution's until the sweep attributes it.
        assertThat(productOfBankSide(lotOrder)).isNull();
        assertThat(attribution.sweep()).isEqualTo(1);
        assertThat(productOfBankSide(lotOrder)).isEqualTo(LINE);
        assertThat(productOfBankSide(personalOrder)).isEqualTo(PERSONAL);
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT coalesce(product_type, '-') FROM accounting.vouchers WHERE credit_account_id = :loan""",
                new MapSqlParameterSource("loan", personalLoan), String.class)).containsExactly(PERSONAL);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM accounting.journal_entries e JOIN accounting.vouchers v ON v.voucher_id = e.voucher_id
                 WHERE e.product_type IS DISTINCT FROM v.product_type""", new MapSqlParameterSource(), Integer.class))
                .as("each entry carries its voucher's product").isZero();

        Map<String, BigDecimal> whole = closing(queries.trialBalance(MAY, List.of(), List.of()));
        Map<String, BigDecimal> personal = closing(queries.trialBalance(MAY, List.of(), List.of(PERSONAL)));
        Map<String, BigDecimal> distributor = closing(queries.trialBalance(MAY, List.of(), List.of(LINE)));
        Map<String, BigDecimal> institution = closing(queries.trialBalance(MAY, List.of(),
                List.of(LedgerQueryDao.INSTITUCION)));

        assertThat(personal.get(AccountCodes.CARTERA_VIGENTE)).isEqualByComparingTo("10000.00");
        assertThat(personal.get(AccountCodes.SPEI_POR_LIQUIDAR)).isEqualByComparingTo("0");
        assertThat(personal.get(AccountCodes.BANCOS)).isEqualByComparingTo("-10000.00");
        assertThat(distributor.get(AccountCodes.GASTO_COMISIONES)).isEqualByComparingTo("600.00");
        assertThat(distributor.get(AccountCodes.COMISIONES_POR_PAGAR)).isEqualByComparingTo("0");
        assertThat(distributor.get(AccountCodes.ISR_RETENIDO_POR_ENTERAR)).isEqualByComparingTo("-60.00");
        assertThat(distributor.get(AccountCodes.SPEI_POR_LIQUIDAR)).isEqualByComparingTo("0");
        assertThat(distributor.get(AccountCodes.BANCOS)).isEqualByComparingTo("-572.00");
        assertThat(institution.get(AccountCodes.BANCOS)).isEqualByComparingTo("50000.00");
        assertThat(whole.get(AccountCodes.BANCOS)).isEqualByComparingTo("39428.00");

        // Each product's trial balance balances, and the parts add up to the whole account by account.
        for (Map<String, BigDecimal> part : List.of(personal, distributor, institution)) {
            assertThat(part.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
        }
        Map<String, BigDecimal> sum = new HashMap<>();
        for (Map<String, BigDecimal> part : List.of(personal, distributor, institution)) {
            part.forEach((code, v) -> sum.merge(code, v, BigDecimal::add));
        }
        whole.forEach((code, v) -> assertThat(sum.getOrDefault(code, BigDecimal.ZERO)).as(code).isEqualByComparingTo(v));

        assertThat(queries.productMovements(MAY, List.of())).extracting(ProductMovementView::productType)
                .containsExactlyInAnyOrder(PERSONAL, LINE, LedgerQueryDao.INSTITUCION);
        ProductSchemeView scheme = queries.productScheme(PERSONAL, null, null);
        assertThat(scheme.concepts()).extracting(SchemeConceptView::triggerEvent)
                .contains("ACCOUNT_ACTIVATED", "DISPOSITION_AT_ORIGINATION", "TREASURY_STP_ORDER");
        assertThat(scheme.concepts()).filteredOn(c -> c.triggerEvent().equals("DISPOSITION_AT_ORIGINATION"))
                .singleElement().satisfies(c -> assertThat(c.pairs()).singleElement().satisfies(p -> {
                    assertThat(p.debitAccount()).isEqualTo(AccountCodes.CARTERA_VIGENTE);
                    assertThat(p.creditAccount()).isEqualTo(AccountCodes.SPEI_POR_LIQUIDAR);
                    assertThat(p.amount()).isEqualByComparingTo("10000.00");
                }));

        CloseCheck capital = check(MAY, "CAPITAL_1201_POR_PRODUCTO");
        assertThat(capital.ok()).as(capital.detail()).isTrue();
        assertThat(capital.blocking()).isFalse();
        assertThat(capital.detail()).contains("PERSONAL_LOAN $10,000.00");

        List<DimensionBalanceView> byProduct = ledgerQueries.trialBalanceByProduct(MAY);
        assertThat(byProduct).filteredOn(r -> PERSONAL.equals(r.productType())
                        && AccountCodes.CARTERA_VIGENTE.equals(r.accountCode()))
                .singleElement().satisfies(r -> {
                    assertThat(r.orgUnitCode()).isEqualTo("S_UNO");
                    assertThat(r.closingBalance()).isEqualByComparingTo("10000.00");
                });
    }

    @Test
    @DisplayName("the sweep attributes only open months: a closed month keeps what it closed with")
    void aClosedMonthIsLeftAsItClosed() {
        may();
        jdbc.update("UPDATE accounting.accounting_periods SET status = 'CLOSED' WHERE period = :p",
                new MapSqlParameterSource("p", MAY));

        assertThat(attribution.sweep()).isZero();
        assertThat(productOfBankSide(lotOrder)).isNull();
    }

    @Test
    @DisplayName("a loan's entry attributed to another product shows in the checks by product, not in the "
            + "account-by-account one")
    void aWrongProductShows() {
        may();
        attribution.sweep();
        // 300 of the personal loan's principal moved to another product: the loan's own balance is untouched.
        entry("move-1", personalLoan, AccountCodes.CARTERA_VIGENTE, AccountCodes.BANCOS, "300.00", "SME_LOAN");
        entry("move-2", personalLoan, AccountCodes.BANCOS, AccountCodes.CARTERA_VIGENTE, "300.00", PERSONAL);

        CloseCheck byAccount = check(MAY, "CAPITAL_1201");
        CloseCheck byProduct = check(MAY, "CAPITAL_1201_POR_PRODUCTO");
        assertThat(byAccount.ok()).as(byAccount.detail()).isTrue();
        assertThat(byProduct.ok()).isFalse();
        assertThat(byProduct.blocking()).isFalse();
        assertThat(byProduct.detail())
                .contains("2 de 2 productos no concilian")
                .contains("PERSONAL_LOAN: cartera $10,000.00, mayor $9,700.00")
                .contains("SME_LOAN: cartera $0.00, mayor $300.00");
    }

    @Test
    @DisplayName("the year-end close takes each product's result to retained earnings: January starts at zero for each")
    void theYearEndByProduct() {
        UUID loan = UUID.randomUUID();
        entryIn("ye-1", loan, "1203", "4101", "1000.00", PERSONAL, "S_UNO", "202503");
        entryIn("ye-2", loan, AccountCodes.GASTO_COMISIONES, AccountCodes.COMISIONES_POR_PAGAR, "300.00", LINE, "S_UNO",
                "202511");
        entryIn("ye-3", loan, "1203", "4103", "200.00", PERSONAL, "S_DOS", "202512");

        CloseCheck check = yearEnd.close(2025, MX);

        assertThat(check.items()).isEqualTo(3);
        assertThat(check.amount()).isEqualByComparingTo("900.00");
        assertThat(jdbc.queryForList("""
                SELECT product_type FROM accounting.vouchers WHERE trigger_event = 'YEAR_END_CLOSE' ORDER BY 1""",
                new MapSqlParameterSource(), String.class)).containsExactly(LINE, PERSONAL, PERSONAL);
        Map<String, BigDecimal> personal = closing(queries.trialBalance("202601", List.of(), List.of(PERSONAL)));
        Map<String, BigDecimal> distributor = closing(queries.trialBalance("202601", List.of(), List.of(LINE)));
        assertThat(personal.get("4101")).isEqualByComparingTo("0");
        assertThat(personal.get("4103")).isEqualByComparingTo("0");
        assertThat(personal.get(AccountCodes.RESULTADO_EJERCICIOS_ANTERIORES)).isEqualByComparingTo("-1200.00");
        assertThat(distributor.get(AccountCodes.GASTO_COMISIONES)).isEqualByComparingTo("0");
        assertThat(distributor.get(AccountCodes.RESULTADO_EJERCICIOS_ANTERIORES)).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("performance: 200,000 entries of four products and five branches split by product, and 20,000 bank "
            + "sides attributed by the sweep, in seconds")
    void performance() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        // 20,000 vouchers of ten entries each, product and branch by voucher.
        jdbc.update("""
                INSERT INTO accounting.vouchers (voucher_id, voucher_type, period, folio, voucher_date, concept,
                       source_event_id, trigger_event, total_debit, total_credit, product_type, org_unit_code)
                SELECT md5('v' || v)::uuid, 'DIARIO', '202605', v, now(), 'prueba', 'perf-v-' || v, 'PERF', 10, 10,
                       (ARRAY['PERSONAL_LOAN', 'DISTRIBUTOR_LINE', 'REVOLVING_CARD', 'SME_LOAN'])[1 + v % 4],
                       'S_' || (v % 5)
                  FROM generate_series(0, 19999) v""", none);
        jdbc.update("""
                INSERT INTO accounting.journal_entries (source_event_id, trigger_event, debit_account, credit_account,
                       amount, description, period, posting_date, voucher_id, product_type, org_unit_code, line_no)
                SELECT 'perf-' || g, 'PERF', CASE WHEN g % 2 = 0 THEN '1201' ELSE '1203' END, '1101', 1 + g % 1000,
                       'prueba', '202605', now(), md5('v' || (g / 10))::uuid,
                       (ARRAY['PERSONAL_LOAN', 'DISTRIBUTOR_LINE', 'REVOLVING_CARD', 'SME_LOAN'])[1 + (g / 10) % 4],
                       'S_' || ((g / 10) % 5), 1 + g % 10
                  FROM generate_series(0, 199999) g""", none);
        jdbc.update("""
                INSERT INTO accounting.account_balance_shadows (credit_account_id, obligor_party_id, product_type)
                SELECT md5('loan' || g)::uuid, md5('party' || g)::uuid,
                       (ARRAY['PERSONAL_LOAN', 'DISTRIBUTOR_LINE', 'REVOLVING_CARD', 'SME_LOAN'])[1 + g % 4]
                  FROM generate_series(1, 20000) g""", none);
        jdbc.update("""
                INSERT INTO accounting.payout_links (payment_request_id, credit_account_id, amount)
                SELECT md5('order' || g)::uuid, md5('loan' || g)::uuid, 100 FROM generate_series(1, 20000) g""", none);
        jdbc.update("""
                INSERT INTO accounting.vouchers (voucher_type, period, folio, voucher_date, concept, source_event_id,
                       trigger_event, total_debit, total_credit, clearing_ref)
                SELECT 'EGRESO', '202605', g, now(), 'prueba', 'bank-' || g, 'TREASURY_STP_ORDER', 100, 100,
                       md5('order' || g)::uuid::text
                  FROM generate_series(1, 20000) g""", none);
        jdbc.update("INSERT INTO accounting.accounting_periods (period, status) VALUES ('202605', 'OPEN') "
                + "ON CONFLICT (period) DO NOTHING", none);

        long start = System.nanoTime();
        List<TrialBalanceRowView> personal = queries.trialBalance(MAY, List.of(), List.of(PERSONAL));
        List<ProductMovementView> movements = queries.productMovements(MAY, List.of());
        List<DimensionBalanceView> byProduct = ledgerQueries.trialBalanceByProduct(MAY);
        long queried = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        int attributed = attribution.sweep();
        long swept = (System.nanoTime() - start) / 1_000_000;

        log.info("Balanza por producto, movimiento y balanza por producto·sucursal de 200000 asientos en {} ms; "
                + "{} lados del banco atribuidos en {} ms", queried, attributed, swept);
        assertThat(closing(personal).get(AccountCodes.CARTERA_VIGENTE)).isPositive();
        assertThat(movements).extracting(ProductMovementView::productType)
                .containsExactlyInAnyOrder(PERSONAL, LINE, "REVOLVING_CARD", "SME_LOAN");
        assertThat(movements).allSatisfy(m -> assertThat(m.vouchers()).isEqualTo(5_000));
        assertThat(byProduct).hasSize(4 * 5 * 3);
        assertThat(attributed).isEqualTo(20_000);
        assertThat(queried).isLessThan(10_000L);
        assertThat(swept).isLessThan(10_000L);
    }

    // ── The story ─────────────────────────────────────────────────────────────────────────────────────────

    private void may() {
        posting.onAccountActivated(new AccountActivation("act-personal", personalLoan, UUID.randomUUID(),
                new BigDecimal("10000.00"), new BigDecimal("10000.00"), "S_UNO", PERSONAL, noon("2026-05-04"), 12));
        payouts.onOrderAccepted(personalOrder, personalLoan, personalLoan.toString(), new BigDecimal("10000.00"));
        bankOrder(personalOrder, "10000.00", "2026-05-04");

        posting.onAccountActivated(new AccountActivation("act-line", line, distributor, new BigDecimal("20000.00"),
                null, "S_DOS", LINE, noon("2026-05-04"), null));
        commissions.onCommissionAccrued(new Accrual(UUID.randomUUID(), line, distributor, LINE, new BigDecimal("600.00"),
                new BigDecimal("96.00"), "DISTRIBUTOR_INTEREST_SHARE", null, noon("2026-05-10")));
        LotPayout payout = new LotPayout(lot, 1, distributor, LINE, new BigDecimal("600.00"), new BigDecimal("96.00"),
                new BigDecimal("60.00"), new BigDecimal("64.00"), new BigDecimal("696.00"), new BigDecimal("572.00"),
                noon("2026-05-12"));
        commissions.onLotPayoutRequested(payout);
        bankOrder(lotOrder, "572.00", "2026-05-12");
        payouts.onItemOrderAccepted(lotOrder, payout.itemRef(), payout.itemRef(), payout.net());

        ledger.post(new VoucherRequest("funding-1", "FUNDING_DRAWDOWN", noon("2026-05-02"), null, null, null,
                "Disposición de la línea de fondeo"), List.of(new Pair(AccountCodes.BANCOS, "2201",
                new BigDecimal("50000.00"), "Fondeo")));
    }

    /** The bank reports the order out: treasury's cash book records it and gives it its bank side, as the listener does. */
    private void bankOrder(UUID order, String amount, String day) {
        UUID id = UUID.randomUUID();
        String fact = "settled-" + order;
        cashBook.record(id, "STP_ORDER", "DEBIT", new BigDecimal(amount), LocalDate.parse(day), order.toString(), fact);
        bankSide.onCashMovement(id, "STP_ORDER", fact, new BigDecimal(amount), LocalDate.parse(day), order.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────────────

    private String productOfBankSide(UUID order) {
        return jdbc.queryForObject("""
                SELECT product_type FROM accounting.vouchers
                 WHERE trigger_event = 'TREASURY_STP_ORDER' AND clearing_ref = :order""",
                new MapSqlParameterSource("order", order.toString()), String.class);
    }

    private void entry(String source, UUID loan, String debit, String credit, String amount, String product) {
        entryIn(source, loan, debit, credit, amount, product, "S_UNO", MAY);
    }

    private void entryIn(String source, UUID loan, String debit, String credit, String amount, String product,
                         String unit, String period) {
        jdbc.update("""
                INSERT INTO accounting.journal_entries (source_event_id, trigger_event, credit_account_id, debit_account,
                       credit_account, amount, description, period, posting_date, product_type, org_unit_code)
                VALUES (:source, 'PRUEBA', :loan, :debit, :credit, :amount, 'prueba', :period, now(), :product, :unit)""",
                new MapSqlParameterSource("source", source).addValue("loan", loan).addValue("debit", debit)
                        .addValue("credit", credit).addValue("amount", new BigDecimal(amount))
                        .addValue("period", period).addValue("product", product).addValue("unit", unit));
    }

    private CloseCheck check(String period, String code) {
        LocalDate last = LocalDate.parse(period.substring(0, 4) + "-" + period.substring(4) + "-01").plusMonths(1).minusDays(1);
        return reconciliation.reconcile(period, last, last.atStartOfDay(MX).toInstant(), true).stream()
                .filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    private static Map<String, BigDecimal> closing(List<TrialBalanceRowView> rows) {
        Map<String, BigDecimal> out = new HashMap<>();
        rows.forEach(r -> out.put(r.accountCode(), r.closingBalance()));
        return out;
    }

    private static Instant noon(String day) {
        return LocalDate.parse(day).atTime(LocalTime.NOON).atZone(MX).toInstant();
    }
}
