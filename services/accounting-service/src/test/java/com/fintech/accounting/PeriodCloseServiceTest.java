package com.fintech.accounting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.CloseReport;
import com.fintech.accounting.application.CloseReport.CloseCheck;
import com.fintech.accounting.application.LedgerViews.DimensionBalanceView;
import com.fintech.accounting.application.LedgerViews.TrialBalanceRowView;
import com.fintech.accounting.application.port.out.AccountingPeriodRepository;
import com.fintech.accounting.application.port.out.CloseReconciliationQueries;
import com.fintech.accounting.application.port.out.PeriodCloseRepository;
import com.fintech.accounting.application.port.out.PeriodCloseRepository.StoredClose;
import com.fintech.accounting.application.service.FeeDeferralService;
import com.fintech.accounting.application.service.YearEndCloseService;
import com.fintech.accounting.application.service.PeriodCloseService;
import com.fintech.accounting.application.service.PeriodNotReconciledException;
import com.fintech.accounting.application.service.ProductAttributionService;
import com.fintech.accounting.domain.AccountingPeriod;
import com.fintech.accounting.infrastructure.adapter.out.persistence.LedgerQueryDao;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The month-end close: it books the month's deferred fees, reconciles, and publishes the month only when every
 * blocking check reconciles; otherwise the month stays open and the report says why.
 */
class PeriodCloseServiceTest {

    private static final String OCT = "202610";

    private final AccountingPeriodRepository periods = mock(AccountingPeriodRepository.class);
    private final PeriodCloseRepository closes = mock(PeriodCloseRepository.class);
    private final CloseReconciliationQueries reconciliation = mock(CloseReconciliationQueries.class);
    private final FeeDeferralService feeDeferral = mock(FeeDeferralService.class);
    private final YearEndCloseService yearEnd = mock(YearEndCloseService.class);
    private final LedgerQueryDao ledger = mock(LedgerQueryDao.class);
    private final ProductAttributionService productAttribution = mock(ProductAttributionService.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private PeriodCloseService service;

    @BeforeEach
    void setUp() {
        AccountingProperties properties = new AccountingProperties();
        properties.setZone("America/Mexico_City");
        service = new PeriodCloseService(periods, closes, reconciliation, feeDeferral, yearEnd, ledger, properties, json,
                productAttribution);
        when(periods.findById(OCT)).thenReturn(Optional.of(AccountingPeriod.open(OCT)));
        when(periods.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledger.trialBalance(eq(OCT), any())).thenReturn(List.of(new TrialBalanceRowView("1201",
                "Cartera de crédito vigente", "ASSET", new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"),
                BigDecimal.ZERO, new BigDecimal("1000"))));
        when(ledger.trialBalanceByProduct(OCT)).thenReturn(List.of(new DimensionBalanceView("PERSONAL_LOAN", "S_UNO",
                "1201", "Cartera de crédito vigente", "ASSET", new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1000"))));
    }

    private static CloseCheck ok(String code) {
        return new CloseCheck(code, code, true, true, 0, BigDecimal.ZERO, "en orden");
    }

    private static CloseCheck off(String code, boolean blocking) {
        return new CloseCheck(code, "Capital: cartera contra el mayor 1201", false, blocking, 2, new BigDecimal("150.25"),
                "2 de 40 créditos no concilian por $150.25");
    }

    @Test
    @DisplayName("a month that reconciles is closed, after attributing products and booking its deferred fees, and its "
            + "trial balance is kept, whole and by product and branch")
    void closesWhenItReconciles() {
        when(reconciliation.reconcile(eq(OCT), eq(LocalDate.of(2026, 10, 31)), any(), eq(true)))
                .thenReturn(List.of(ok("CARTERA_CERRADA"), ok("CAPITAL_1201"),
                        new CloseCheck("EXTEMPORANEAS", "Extemporáneas", false, false, 1, BigDecimal.TEN, "una")));

        CloseReport report = service.close(OCT);

        assertThat(report.reconciled()).isTrue();
        assertThat(report.closedAt()).isNotNull();
        // Products are attributed and the fees of the month booked before the reconciliation that checks them.
        InOrder order = inOrder(productAttribution, feeDeferral, reconciliation);
        order.verify(productAttribution).sweep();
        order.verify(feeDeferral).amortize(eq(OCT), eq(Instant.parse("2026-11-01T05:59:59Z")));
        order.verify(reconciliation).reconcile(eq(OCT), any(), any(), eq(true));
        ArgumentCaptor<AccountingPeriod> saved = ArgumentCaptor.forClass(AccountingPeriod.class);
        verify(periods).save(saved.capture());
        assertThat(saved.getValue().isOpen()).isFalse();
        ArgumentCaptor<String> trialBalance = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> byProduct = ArgumentCaptor.forClass(String.class);
        verify(closes).save(eq(OCT), eq(CloseReport.RECONCILED), any(), anyString(), trialBalance.capture(),
                byProduct.capture());
        assertThat(trialBalance.getValue()).contains("\"accountCode\":\"1201\"").contains("\"closingBalance\":1000");
        assertThat(byProduct.getValue()).contains("\"productType\":\"PERSONAL_LOAN\"")
                .contains("\"orgUnitCode\":\"S_UNO\"").contains("\"closingBalance\":1000");
    }

    @Test
    @DisplayName("December, once it reconciles, books the year-end close; the other months do not")
    void decemberClosesTheYear() {
        String dec = "202612";
        when(periods.findById(dec)).thenReturn(Optional.of(AccountingPeriod.open(dec)));
        when(reconciliation.reconcile(eq(dec), any(), any(), eq(true))).thenReturn(List.of(ok("CAPITAL_1201")));
        when(yearEnd.close(eq(2026), any())).thenReturn(new CloseCheck("CIERRE_DEL_EJERCICIO", "Cierre del ejercicio",
                true, false, 2, new BigDecimal("1234.56"), "resultado del ejercicio 2026 traspasado a 3201"));

        CloseReport report = service.close(dec);

        assertThat(report.checks()).extracting(CloseCheck::code).contains("CIERRE_DEL_EJERCICIO");
        // Booked after the reconciliation passed, never before: a December with differences keeps its result open.
        InOrder order = inOrder(reconciliation, yearEnd);
        order.verify(reconciliation).reconcile(eq(dec), any(), any(), eq(true));
        order.verify(yearEnd).close(eq(2026), any());

        when(reconciliation.reconcile(eq(OCT), any(), any(), eq(true))).thenReturn(List.of(ok("CAPITAL_1201")));
        service.close(OCT);
        verify(yearEnd).close(eq(2026), any());   // once, by December; October did not call it
    }

    @Test
    @DisplayName("a December with differences stays open and its result is not closed")
    void decemberWithDifferencesDoesNotCloseTheYear() {
        String dec = "202612";
        when(periods.findById(dec)).thenReturn(Optional.of(AccountingPeriod.open(dec)));
        when(reconciliation.reconcile(eq(dec), any(), any(), eq(true))).thenReturn(List.of(off("CAPITAL_1201", true)));

        assertThatThrownBy(() -> service.close(dec)).isInstanceOf(PeriodNotReconciledException.class);
        verify(yearEnd, never()).close(eq(2026), any());
    }

    @Test
    @DisplayName("a month that does not reconcile stays open, and the report says what is off and by how much")
    void staysOpenWithDifferences() {
        when(reconciliation.reconcile(eq(OCT), any(), any(), eq(true)))
                .thenReturn(List.of(ok("CARTERA_CERRADA"), off("CAPITAL_1201", true)));

        assertThatThrownBy(() -> service.close(OCT))
                .isInstanceOf(PeriodNotReconciledException.class)
                .hasMessageContaining("No se puede cerrar el período 202610")
                .hasMessageContaining("Capital: cartera contra el mayor 1201")
                .satisfies(e -> assertThat(((PeriodNotReconciledException) e).report().failures())
                        .extracting(CloseCheck::code).containsExactly("CAPITAL_1201"));

        verify(periods, never()).save(any());
        verify(closes).save(eq(OCT), eq(CloseReport.DIFFERENCES), any(), anyString(), isNull());
    }

    @Test
    @DisplayName("a check that only reports (the bank, late postings) does not keep the month open")
    void reportOnlyChecksDoNotBlock() {
        when(reconciliation.reconcile(eq(OCT), any(), any(), eq(true)))
                .thenReturn(List.of(ok("CAPITAL_1201"), off("EXTEMPORANEAS", false)));

        assertThat(service.close(OCT).reconciled()).isTrue();
    }

    @Test
    @DisplayName("closing a closed month returns the close already made: nothing is booked twice")
    void closingTwice() throws Exception {
        AccountingPeriod closed = AccountingPeriod.open(OCT);
        closed.close();
        when(periods.findById(OCT)).thenReturn(Optional.of(closed));
        String checks = json.writeValueAsString(List.of(ok("CAPITAL_1201")));
        when(closes.find(OCT)).thenReturn(Optional.of(new StoredClose(OCT, CloseReport.RECONCILED,
                Instant.parse("2026-11-01T17:00:00Z"), 1, checks, "[]")));

        CloseReport report = service.close(OCT);

        assertThat(report.reconciled()).isTrue();
        assertThat(report.checks()).extracting(CloseCheck::code).containsExactly("CAPITAL_1201");
        verify(feeDeferral, never()).amortize(anyString(), any());
        verify(reconciliation, never()).reconcile(anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("the reconciliation of an open month is a dry run: nothing is booked, the month's fees are reported as pending")
    void previewBooksNothing() {
        when(reconciliation.reconcile(eq(OCT), any(), any(), eq(false))).thenReturn(List.of(ok("CAPITAL_1201")));

        CloseReport report = service.reconciliation(OCT);

        assertThat(report.preview()).isTrue();
        assertThat(report.closedAt()).isNull();
        verify(feeDeferral, never()).amortize(anyString(), any());
        verify(closes, never()).save(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("un mes que no ha terminado se concilia a la fecha: lo que se mide el último día queda pendiente, no es diferencia")
    void aMonthInProgressIsCheckedToDate() {
        BusinessClock.current().shiftTo(LocalDate.of(2026, 10, 4));
        try {
            // To date: the portfolio closed yesterday (the 3rd); the reserve and the funding are measured on the 31st.
            when(reconciliation.reconcile(eq(OCT), eq(LocalDate.of(2026, 10, 3)), any(), eq(false))).thenReturn(List.of(
                    ok("CARTERA_CERRADA"), ok("CAPITAL_1201"),
                    new CloseCheck("RESERVA_MEDIDA", "Reserva IFRS 9 medida a la fecha del cierre", false, true, 53,
                            new BigDecimal("3038650.14"), "53 de 53 créditos sin reserva medida al 3 oct 2026"),
                    new CloseCheck("FONDEO_DEVENGADO", "Intereses del fondeo devengados al último día", false, true, 1,
                            null, "1 de 1 líneas sin devengar al 3 oct 2026: Banco de fomento")));

            CloseReport report = service.reconciliation(OCT);

            assertThat(report.outcome()).isEqualTo(CloseReport.IN_PROGRESS);
            assertThat(report.preview()).isTrue();
            assertThat(report.failures()).isEmpty();
            assertThat(report.pendingChecks()).extracting(CloseCheck::code)
                    .containsExactly("RESERVA_MEDIDA", "FONDEO_DEVENGADO");
            assertThat(report.pendingChecks()).allSatisfy(c -> assertThat(c.detail()).startsWith("Se mide al cierre del 31 de octubre"));
            assertThat(report.checks()).filteredOn(c -> c.code().equals("CARTERA_CERRADA")).singleElement()
                    .satisfies(c -> assertThat(c.title()).isEqualTo("Cartera cerró el día anterior"));
            assertThat(report.summary()).startsWith("El período 202610 sigue en curso y va en orden");
            verify(feeDeferral, never()).amortize(anyString(), any());
        } finally {
            BusinessClock.current().resetShift();
        }
    }

    @Test
    @DisplayName("un mes en curso con una diferencia real la dice: lo pendiente no la tapa")
    void aMonthInProgressStillShowsARealDifference() {
        BusinessClock.current().shiftTo(LocalDate.of(2026, 10, 20));
        try {
            when(reconciliation.reconcile(eq(OCT), eq(LocalDate.of(2026, 10, 19)), any(), eq(false))).thenReturn(List.of(
                    off("CAPITAL_1201", true),
                    new CloseCheck("FONDEO_DEVENGADO", "Intereses del fondeo", false, true, 1, null, "1 sin devengar")));

            CloseReport report = service.reconciliation(OCT);

            assertThat(report.outcome()).isEqualTo(CloseReport.DIFFERENCES);
            assertThat(report.failures()).extracting(CloseCheck::code).containsExactly("CAPITAL_1201");
            assertThat(report.pendingChecks()).extracting(CloseCheck::code).containsExactly("FONDEO_DEVENGADO");
        } finally {
            BusinessClock.current().resetShift();
        }
    }

    @Test
    @DisplayName("un cierre guardado antes de que existiera «pendiente» se lee igual: nada pendiente")
    void aCloseStoredBeforePendingReadsTheSame() throws Exception {
        CloseCheck old = json.readValue("""
                {"code":"CAPITAL_1201","title":"Capital","ok":true,"blocking":true,"items":0,"amount":0,"detail":"en orden"}""",
                CloseCheck.class);
        assertThat(old.pending()).isFalse();
        assertThat(CloseReport.of(OCT, Instant.now(), Instant.now(), false, List.of(old)).outcome())
                .isEqualTo(CloseReport.RECONCILED);
    }

    @Test
    @DisplayName("rendimiento: una corrida en curso de mil comprobaciones se arma en milisegundos")
    void inProgressPerformance() {
        BusinessClock.current().shiftTo(LocalDate.of(2026, 10, 10));
        try {
            List<CloseCheck> many = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                many.add(i % 100 == 0 ? new CloseCheck("RESERVA_MEDIDA", "Reserva", false, true, 1, BigDecimal.ONE, "una")
                        : ok("C" + i));
            }
            when(reconciliation.reconcile(eq(OCT), any(), any(), eq(false))).thenReturn(many);
            long start = System.nanoTime();
            CloseReport report = null;
            for (int i = 0; i < 100; i++) report = service.reconciliation(OCT);
            assertThat((System.nanoTime() - start) / 100 / 1_000).as("µs por corrida").isLessThan(20_000L);
            assertThat(report.outcome()).isEqualTo(CloseReport.IN_PROGRESS);
        } finally {
            BusinessClock.current().resetShift();
        }
    }

    @Test
    @DisplayName("a period that is not YYYYMM is rejected")
    void invalidPeriod() {
        assertThatThrownBy(() -> service.close("2026-10")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("performance: a report of a thousand checks summarizes in milliseconds")
    void summaryPerformance() {
        List<CloseCheck> many = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) many.add(i % 10 == 0 ? off("C" + i, true) : ok("C" + i));
        CloseReport report = CloseReport.of(OCT, Instant.now(), null, true, many);
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) report.summary();
        assertThat((System.nanoTime() - start) / 100 / 1_000).as("µs por resumen").isLessThan(5_000L);
        assertThat(report.failures()).hasSize(100);
    }
}
