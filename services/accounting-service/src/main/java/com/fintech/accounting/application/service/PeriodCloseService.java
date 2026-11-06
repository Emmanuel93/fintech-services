package com.fintech.accounting.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.accounting.application.AccountingProperties;
import com.fintech.accounting.application.CloseReport;
import com.fintech.accounting.application.CloseReport.CloseCheck;
import com.fintech.accounting.application.LedgerViews.DimensionBalanceView;
import com.fintech.accounting.application.LedgerViews.TrialBalanceRowView;
import com.fintech.accounting.application.port.out.AccountingPeriodRepository;
import com.fintech.accounting.application.port.out.CloseReconciliationQueries;
import com.fintech.accounting.application.port.out.PeriodCloseRepository;
import com.fintech.accounting.application.port.out.PeriodCloseRepository.StoredClose;
import com.fintech.accounting.domain.AccountingPeriod;
import com.fintech.accounting.infrastructure.adapter.out.persistence.LedgerQueryDao;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The month-end close. Every fact is booked as it happens, each in the period of its fact; at the end of the month
 * the close books what belongs to the month as a whole and publishes the month only if it reconciles:
 * <ol>
 *   <li>attributes to their product the vouchers booked before it was known (the bank side of an order or a credit
 *       whose item arrived later), so the trial balance by product it keeps is complete;</li>
 *   <li>books the month's share of the deferred opening fees (IFRS 9 effective interest), dated on its last moment;</li>
 *   <li>checks the subledgers are complete: the portfolio closed the month's last day, and risk measured the expected
 *       credit loss of every account with exposure at that date (IFRS 9 §5.5);</li>
 *   <li>reconciles the ledger with the subledgers account by account — principal (1201), receivable (1203), loss
 *       allowance (1290), deferred fees (2150) — and its own invariants: balanced vouchers, order accounts in pairs,
 *       nothing against equity, entries in centavos;</li>
 *   <li>when every blocking check reconciles, closes the month and keeps the trial balance it published, whole and
 *       by product and branch; when not, the month stays open and the report says what is off;</li>
 *   <li>in December, once it reconciles, books the year-end close: the year's result to retained earnings (3201) in
 *       period 13, so the next year's revenue and expenses start from zero.</li>
 * </ol>
 * Closing twice returns the close already made. A fact of a closed month that arrives later is booked in the first
 * open one, marked late, and reported by that month's close.
 */
@Service
public class PeriodCloseService {

    private static final Logger log = LoggerFactory.getLogger(PeriodCloseService.class);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

    private final AccountingPeriodRepository periods;
    private final PeriodCloseRepository closes;
    private final CloseReconciliationQueries reconciliation;
    private final FeeDeferralService feeDeferral;
    private final YearEndCloseService yearEnd;
    private final LedgerQueryDao ledger;
    private final AccountingProperties properties;
    private final ObjectMapper json;
    private final ProductAttributionService productAttribution;

    public PeriodCloseService(AccountingPeriodRepository periods, PeriodCloseRepository closes,
                              CloseReconciliationQueries reconciliation, FeeDeferralService feeDeferral,
                              YearEndCloseService yearEnd, LedgerQueryDao ledger, AccountingProperties properties,
                              ObjectMapper json, ProductAttributionService productAttribution) {
        this.productAttribution = productAttribution;
        this.yearEnd = yearEnd;
        this.periods = periods;
        this.closes = closes;
        this.reconciliation = reconciliation;
        this.feeDeferral = feeDeferral;
        this.ledger = ledger;
        this.properties = properties;
        this.json = json;
    }

    /**
     * Closes {@code period} if it reconciles.
     *
     * @throws PeriodNotReconciledException when a blocking check is off; the month stays open
     */
    public CloseReport close(String period) {
        YearMonth month = parse(period);
        AccountingPeriod current = periods.findById(period).orElse(null);
        if (current != null && !current.isOpen()) {
            return stored(period, current).orElseGet(() -> run(period, month, true, current.getClosedAt()));
        }

        // What was booked before its product was known is attributed first: the close keeps the balance by product.
        productAttribution.sweep();

        // The month's share of the deferred fees belongs to the month: booked before reconciling, on its last moment.
        ZoneId zone = properties.zoneId();
        Instant endOfMonth = month.atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant();
        int amortizations = feeDeferral.amortize(period, endOfMonth);
        if (amortizations > 0) {
            log.info("Cierre {}: {} pólizas de devengo de comisiones de apertura", period, amortizations);
        }

        CloseReport report = run(period, month, true, null);
        if (!report.reconciled()) {
            closes.save(period, report.outcome(), report.runAt(), write(report.checks()), null);
            log.warn("Cierre {} rechazado: {}", period, report.summary());
            throw new PeriodNotReconciledException(report);
        }
        // December reconciled: the year's result goes to retained earnings, in period 13 (December keeps its figures).
        List<CloseCheck> checks = report.checks();
        if (month.getMonthValue() == 12) {
            checks = new ArrayList<>(checks);
            checks.add(yearEnd.close(month.getYear(), zone));
        }
        AccountingPeriod p = current != null ? current : AccountingPeriod.open(period);
        p.close();
        periods.save(p);
        List<TrialBalanceRowView> trialBalance = ledger.trialBalance(period, List.of());
        List<DimensionBalanceView> byProduct = ledger.trialBalanceByProduct(period);
        closes.save(period, report.outcome(), report.runAt(), write(checks), write(trialBalance), write(byProduct));
        log.info("Cierre {} conciliado: {} comprobaciones, {} cuentas en la balanza, {} renglones por producto y sucursal",
                period, checks.size(), trialBalance.size(), byProduct.size());
        return new CloseReport(period, report.outcome(), report.runAt(), p.getClosedAt(), false, List.copyOf(checks));
    }

    /**
     * The reconciliation of {@code period}: for a closed month, the one its close ran; for an open month, a dry run that
     * books nothing (the month's deferred fees are reported as what the close will book).
     */
    public CloseReport reconciliation(String period) {
        YearMonth month = parse(period);
        AccountingPeriod current = periods.findById(period).orElse(null);
        if (current != null && !current.isOpen()) {
            return stored(period, current).orElseGet(() -> run(period, month, true, current.getClosedAt()));
        }
        CloseReport preview = BusinessClock.current().today().isAfter(month.atEndOfMonth())
                ? run(period, month, false, null)
                : inProgress(period, month);
        if (month.getMonthValue() != 12) return preview;
        List<CloseCheck> checks = new ArrayList<>(preview.checks());
        checks.add(yearEnd.preview(month.getYear()));
        return CloseReport.of(period, preview.runAt(), null, true, checks);
    }

    /**
     * The dry run of a month whose last day has not come. Before, it measured the month as of its last day and said
     * «differences» on the 4th because the portfolio had not closed the 30th, the reserve was not measured at the 30th
     * and the funding had not accrued to the 30th: things that cannot have happened yet. Now what can be checked today
     * is checked as of the last day the nightly chain closed (yesterday), and what is measured on the last day says
     * so: pending, not a difference.
     */
    private CloseReport inProgress(String period, YearMonth month) {
        ZoneId zone = properties.zoneId();
        LocalDate lastDay = month.atEndOfMonth();
        LocalDate asOf = BusinessClock.current().today().minusDays(1);
        List<CloseCheck> checks = new ArrayList<>();
        for (CloseCheck c : reconciliation.reconcile(period, asOf, asOf.atStartOfDay(zone).toInstant(), false)) {
            if (!c.ok() && MEASURED_ON_THE_LAST_DAY.contains(c.code())) {
                checks.add(c.pendingUntilMonthEnd(lastDay));
            } else if ("CARTERA_CERRADA".equals(c.code())) {
                checks.add(c.titled("Cartera cerró el día anterior"));
            } else {
                checks.add(c);
            }
        }
        return CloseReport.of(period, BusinessClock.current().now(), null, true, checks);
    }

    /** The checks whose measure is taken on the month's last day: the IFRS 9 reserve and the funding's interest. */
    private static final Set<String> MEASURED_ON_THE_LAST_DAY = Set.of("RESERVA_MEDIDA", "FONDEO_DEVENGADO");

    private CloseReport run(String period, YearMonth month, boolean amortized, Instant closedAt) {
        ZoneId zone = properties.zoneId();
        LocalDate lastDay = month.atEndOfMonth();
        List<CloseCheck> checks = reconciliation.reconcile(period, lastDay, lastDay.atStartOfDay(zone).toInstant(),
                amortized);
        return CloseReport.of(period, BusinessClock.current().now(), closedAt, !amortized, checks);
    }

    private Optional<CloseReport> stored(String period, AccountingPeriod closed) {
        return closes.find(period).filter(s -> CloseReport.RECONCILED.equals(s.outcome()))
                .map(s -> new CloseReport(period, s.outcome(), s.runAt(), closed.getClosedAt(), false, read(s)));
    }

    private List<CloseCheck> read(StoredClose s) {
        try {
            return json.readValue(s.checksJson(), new TypeReference<List<CloseCheck>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("El cierre guardado de " + s.period() + " no se puede leer", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo guardar el cierre", e);
        }
    }

    private static YearMonth parse(String period) {
        try {
            return YearMonth.parse(period, PERIOD);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Período inválido: " + period + " (se espera AAAAMM)");
        }
    }
}
