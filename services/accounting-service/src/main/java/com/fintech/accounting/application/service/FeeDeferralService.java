package com.fintech.accounting.application.service;

import com.fintech.accounting.application.port.out.DeferredFeeRepository;
import com.fintech.accounting.application.port.out.DeferredFeeRepository.DeferredFee;
import com.fintech.accounting.application.service.VoucherPostingService.Pair;
import com.fintech.accounting.application.service.VoucherPostingService.VoucherRequest;
import com.fintech.accounting.domain.AccountCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the effective interest rate spreads over the life of the loan (IFRS 9 B5.4.1-B5.4.8): the opening fee charged
 * to the client and the transaction costs paid to originate it (the promoter's commission). Neither is revenue or
 * expense the day it happens: both adjust the loan's interest revenue over its life.
 * <ul>
 *   <li>the fee is charged to the receivable and credited to 2150; each month its share moves from 2150 to interest
 *       revenue (4106);</li>
 *   <li>the cost is an asset (1205) credited to what is owed to the promoter; each month its share moves from 1205
 *       against the same interest revenue (4106), lowering it.</li>
 * </ul>
 * The share is straight-line by month, within cents of the effective-rate allocation for these terms; when the loan is
 * settled or written off, what is left is recognized at once. The fee used to be recognized as fee income (4103): under
 * the effective interest method it is interest revenue, presented apart from the contractual interest (4101).
 *
 * <p>Every recognition is a voucher with its own key (deferral, period), so closing a month twice recognizes nothing
 * twice.
 */
@Service
public class FeeDeferralService {

    private static final Logger log = LoggerFactory.getLogger(FeeDeferralService.class);
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");
    public static final String TRIGGER = "FEE_AMORTIZATION";

    private final DeferredFeeRepository fees;
    private final VoucherPostingService ledger;

    public FeeDeferralService(DeferredFeeRepository fees, VoucherPostingService ledger) {
        this.fees = fees;
        this.ledger = ledger;
    }

    /** The loan's life in months: its periods at its payment frequency, at least one. */
    public static Integer termMonths(Integer termPeriods, String paymentFrequency) {
        if (termPeriods == null || termPeriods <= 0) return null;
        int perYear = switch (paymentFrequency == null ? "MONTHLY" : paymentFrequency.toUpperCase()) {
            case "WEEKLY" -> 52;
            case "BIWEEKLY" -> 26;
            // The calendar fortnight: two a month, 24 a year.
            case "SEMI_MONTHLY", "QUINCENAL" -> 24;
            default -> 12;
        };
        return Math.max(1, (int) Math.ceil(termPeriods * 12.0 / perYear));
    }

    /** Defers an opening fee charged in {@code period} over {@code months} months. */
    public void defer(String sourceEventId, UUID creditAccountId, UUID obligorPartyId, String unit, BigDecimal amount,
                      int months, String period) {
        if (fees.register(new DeferredFee(UUID.randomUUID(), sourceEventId, creditAccountId, obligorPartyId, unit,
                amount, BigDecimal.ZERO, months, period, DeferredFeeRepository.FEE))) {
            log.info("Comisión de apertura diferida creditAccountId={} monto={} meses={} desde={}",
                    creditAccountId, amount, months, period);
        }
    }

    /** Defers a transaction cost of origination paid in {@code period} over {@code months} months. */
    public void deferCost(String sourceEventId, UUID creditAccountId, UUID obligorPartyId, String unit,
                          BigDecimal amount, int months, String period) {
        if (fees.register(new DeferredFee(UUID.randomUUID(), sourceEventId, creditAccountId, obligorPartyId, unit,
                amount, BigDecimal.ZERO, months, period, DeferredFeeRepository.COST))) {
            log.info("Costo de originación diferido creditAccountId={} monto={} meses={} desde={}",
                    creditAccountId, amount, months, period);
        }
    }

    /**
     * Defers a funding line's opening fee paid in {@code period} over the line's {@code months}: amortized into the
     * line's interest expense. Keyed by the funding source, not a loan.
     */
    public void deferFunding(String sourceEventId, UUID fundingSourceId, String unit, BigDecimal amount, int months,
                             String period) {
        if (fees.register(new DeferredFee(UUID.randomUUID(), sourceEventId, fundingSourceId, null, unit, amount,
                BigDecimal.ZERO, months, period, DeferredFeeRepository.FUNDING))) {
            log.info("Comisión de apertura de la línea de fondeo diferida fuente={} monto={} meses={} desde={}",
                    fundingSourceId, amount, months, period);
        }
    }

    /** At a month's close: every active deferral recognizes the part of the months elapsed up to that month. */
    @Transactional
    public int amortize(String period, Instant at) {
        int vouchers = 0;
        for (DeferredFee f : fees.active()) {
            int elapsed = monthsBetween(f.firstPeriod(), period) + 1;
            if (elapsed <= 0) continue;
            BigDecimal target = elapsed >= f.months() ? f.total()
                    : f.total().multiply(BigDecimal.valueOf(elapsed)).divide(BigDecimal.valueOf(f.months()), 2, RoundingMode.HALF_UP);
            String share = " (" + Math.min(elapsed, f.months()) + "/" + f.months() + ")";
            String concept = f.isFunding() ? "Amortización mensual de la comisión de apertura de la línea de fondeo"
                    : f.isCost() ? "Amortización mensual del costo de originación"
                    : "Devengo mensual de la comisión de apertura";
            vouchers += recognize(f, target, "FEE-AMORT:" + f.id() + ":" + period, at, concept + share);
        }
        return vouchers;
    }

    /** The loan was settled or written off: what is left of its fees and costs is recognized now. */
    @Transactional
    public void recognizeRemainder(UUID creditAccountId, String reason, Instant at) {
        for (DeferredFee f : fees.activeOf(creditAccountId)) {
            recognize(f, f.total(), "FEE-AMORT:" + f.id() + ":FINAL", at,
                    (f.isCost() ? "Costo de originación por amortizar reconocido al " : "Comisión de apertura por devengar reconocida al ")
                    + reason);
        }
    }

    /**
     * A reversed or waived opening fee: the part still deferred leaves 2150 (it was never revenue), the rest was
     * already recognized and is reversed from revenue by the caller. Returns the part taken from 2150.
     */
    @Transactional
    public BigDecimal withdraw(UUID creditAccountId, BigDecimal amount) {
        BigDecimal pendiente = amount;
        BigDecimal retirado = BigDecimal.ZERO;
        for (DeferredFee f : fees.activeOf(creditAccountId)) {
            if (f.isCost() || f.isFunding()) continue;
            if (pendiente.signum() <= 0) break;
            BigDecimal toma = pendiente.min(f.pending());
            if (toma.signum() > 0) {
                fees.reduceTotal(f.id(), toma);
                retirado = retirado.add(toma);
                pendiente = pendiente.subtract(toma);
            }
        }
        return retirado;
    }

    /**
     * A reversed origination commission: what was still deferred leaves 1205, and what was already amortized gives the
     * interest revenue back. Returns both parts, or empty when the commission was not deferred.
     */
    @Transactional
    public Optional<Withdrawn> withdrawCost(String sourceEventId) {
        return fees.findBySource(sourceEventId).filter(DeferredFee::isCost).map(f -> {
            BigDecimal pending = f.pending();
            if (pending.signum() > 0) fees.reduceTotal(f.id(), pending);
            return new Withdrawn(pending, f.recognized());
        });
    }

    /** What a reversal takes back: the part still deferred and the part already recognized. */
    public record Withdrawn(BigDecimal deferred, BigDecimal recognized) {}

    private int recognize(DeferredFee f, BigDecimal target, String key, Instant at, String concept) {
        BigDecimal delta = target.min(f.total()).subtract(f.recognized());
        if (delta.signum() <= 0) return 0;
        Pair pair = f.isFunding()
                ? new Pair(AccountCodes.GASTO_INTERESES_FONDEO, AccountCodes.COSTOS_FONDEO_POR_AMORTIZAR, delta, concept)
                : f.isCost()
                ? new Pair(AccountCodes.INGRESOS_TASA_EFECTIVA, AccountCodes.COSTOS_ORIGINACION, delta, concept)
                : new Pair(AccountCodes.COMISIONES_POR_DEVENGAR, AccountCodes.INGRESOS_TASA_EFECTIVA, delta, concept);
        // A funding deferral belongs to the institution's own liability, not to a loan: its voucher names no credit.
        UUID loan = f.isFunding() ? null : f.creditAccountId();
        ledger.post(new VoucherRequest(key, TRIGGER, at, loan, f.obligorPartyId(), f.orgUnitCode(), concept),
                List.of(pair));
        fees.recognize(f.id(), f.recognized().add(delta));
        return 1;
    }

    static int monthsBetween(String from, String to) {
        return (int) ChronoUnit.MONTHS.between(YearMonth.parse(from, PERIOD), YearMonth.parse(to, PERIOD));
    }
}
