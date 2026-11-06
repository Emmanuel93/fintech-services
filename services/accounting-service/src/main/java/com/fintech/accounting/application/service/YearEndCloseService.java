package com.fintech.accounting.application.service;

import com.fintech.accounting.application.CloseReport.CloseCheck;
import com.fintech.accounting.application.port.out.CloseReconciliationQueries;
import com.fintech.accounting.application.port.out.CloseReconciliationQueries.YearBalance;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The year-end close. Revenue and expense accounts measure one year; once December reconciles, the closing voucher
 * takes every one of them to zero against retained earnings (3201), unit by unit, so January starts from nothing and
 * the equity shows the result of the years closed.
 *
 * <p>It is booked in period 13 ({@code YYYY13}), dated 31 December at 23:59:59, as SAT's electronic accounting sends
 * the year's closing trial balance (Anexo 24, month 13): December's trial balance and income statement keep the
 * year's figures; the ledger read from January on no longer carries them. Idempotent: one voucher per unit and year.
 */
@Service
public class YearEndCloseService {

    private static final Logger log = LoggerFactory.getLogger(YearEndCloseService.class);
    static final String TRIGGER = "YEAR_END_CLOSE";
    static final String CODE = "CIERRE_DEL_EJERCICIO";
    private static final String TITLE = "Cierre del ejercicio: resultado a ejercicios anteriores (3201)";

    private final CloseReconciliationQueries queries;
    private final VoucherPostingService ledger;

    public YearEndCloseService(CloseReconciliationQueries queries, VoucherPostingService ledger) {
        this.queries = queries;
        this.ledger = ledger;
    }

    /** Books the closing voucher of {@code year}; returns the check the December close reports. */
    public CloseCheck close(int year, ZoneId zone) {
        Map<String, List<YearBalance>> byUnit = byUnitAndProduct(queries.yearResult(year));
        Instant at = LocalDate.of(year, 12, 31).atTime(23, 59, 59).atZone(zone).toInstant();
        String period = year + "13";
        for (Map.Entry<String, List<YearBalance>> unit : byUnit.entrySet()) {
            List<Pair> pairs = new ArrayList<>();
            for (YearBalance b : unit.getValue()) {
                pairs.add(b.balance().signum() > 0
                        ? new Pair(AccountCodes.RESULTADO_EJERCICIOS_ANTERIORES, b.accountCode(), b.balance(),
                                "Cierre del ejercicio: gasto " + b.accountCode())
                        : new Pair(b.accountCode(), AccountCodes.RESULTADO_EJERCICIOS_ANTERIORES, b.balance().negate(),
                                "Cierre del ejercicio: ingreso " + b.accountCode()));
            }
            YearBalance first = unit.getValue().get(0);
            ledger.postInPeriod(new VoucherRequest("YEAR-END:" + year + ":" + unit.getKey(), TRIGGER, at, null, null,
                    first.orgUnitCode(), "Cierre del ejercicio " + year + ": resultado a ejercicios anteriores")
                    .withProduct(first.productType()), pairs, period);
        }
        BigDecimal result = result(byUnit);
        log.info("Cierre del ejercicio {}: resultado {} traspasado a 3201 en {} unidades·producto", year, result,
                byUnit.size());
        return new CloseCheck(CODE, TITLE, true, false, byUnit.size(), result,
                "resultado del ejercicio " + year + " " + money(result) + " traspasado a 3201 en el periodo " + period
                + " (" + byUnit.size() + " unidades·producto); enero empieza con ingresos y gastos en cero");
    }

    /** What the December close will book, without booking it. */
    public CloseCheck preview(int year) {
        Map<String, List<YearBalance>> byUnit = byUnitAndProduct(queries.yearResult(year));
        BigDecimal result = result(byUnit);
        return new CloseCheck(CODE, TITLE, true, false, byUnit.size(), result,
                "al cerrar diciembre se traspasará el resultado del ejercicio " + year + " (" + money(result)
                + ") a 3201 en el periodo " + year + "13");
    }

    /**
     * The year's result by unit and product: each closing voucher takes one unit's result of one product to 3201, so
     * the trial balance by product also starts January at zero. Keyed {@code <unit>} (or {@code -}), plus
     * {@code :<product>} when there is one: a year closed before the product dimension keeps its keys.
     */
    private static Map<String, List<YearBalance>> byUnitAndProduct(List<YearBalance> balances) {
        Map<String, List<YearBalance>> groups = new LinkedHashMap<>();
        for (YearBalance b : balances) {
            String key = Objects.toString(b.orgUnitCode(), "-") + (b.productType() != null ? ":" + b.productType() : "");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }
        return groups;
    }

    /** Revenue − expenses: the negative of the debit balances. */
    private static BigDecimal result(Map<String, List<YearBalance>> byUnit) {
        return byUnit.values().stream().flatMap(List::stream).map(YearBalance::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add).negate();
    }

    private static String money(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX")).format(v);
    }
}
