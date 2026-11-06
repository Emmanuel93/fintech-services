package com.fintech.statement.application.service.assembly;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * What a credit costs, in the terms the statement must show it (CONDUSEF DT-ENR 36-IX and 37; LTOSF 18 Bis 4).
 *
 * <ul>
 *   <li>{@link #catFromRate}: the CAT of a plan without fees, from its nominal rate and how many periods a year it
 *       pays — {@code (1 + rate / m)^m − 1}. It is what a distributor's placement or a loan without an opening fee
 *       costs; when the account carries the CAT of the offer the client signed, that one is printed instead.</li>
 *   <li>{@link #monthsPayingOnlyTheMinimum}: how long the current revolving balance takes to be paid off paying only
 *       the minimum every month, with no new purchases, and what is paid in total.</li>
 *   <li>{@link #paymentToPayOffIn}: the fixed monthly payment that pays it off in a number of months.</li>
 * </ul>
 * Interest bears IVA (16 %) in the projections, as it is charged.
 */
public final class CreditCost {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal IVA = new BigDecimal("1.16");
    private static final int MAX_MONTHS = 600;

    private CreditCost() {}

    /** The CAT, as a fraction (0.253 is 25.3 %), of a fee-free plan paying {@code periodsPerYear} times a year. */
    public static BigDecimal catFromRate(BigDecimal nominalAnnualRate, int periodsPerYear) {
        if (nominalAnnualRate == null || periodsPerYear <= 0) return null;
        BigDecimal periodic = nominalAnnualRate.divide(BigDecimal.valueOf(periodsPerYear), MC);
        return BigDecimal.ONE.add(periodic).pow(periodsPerYear, MC).subtract(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
    }

    /** Payoff projection paying only the minimum: months and the total paid. */
    public record Payoff(int months, BigDecimal totalPaid, boolean neverPaysOff) {}

    /**
     * @param balance         the revolving balance to pay off
     * @param annualRate      the ordinary annual rate (fraction)
     * @param productShare    the product's minimum as a share of the balance (0.05); null when it sets none
     * @param creditLimit     the line, for the Banxico floor (1.25 %); null when not a card
     * @param bankingFloor    whether the Banxico floor of a credit card applies
     */
    public static Payoff monthsPayingOnlyTheMinimum(BigDecimal balance, BigDecimal annualRate, BigDecimal productShare,
                                                    BigDecimal creditLimit, boolean bankingFloor) {
        BigDecimal b = nz(balance);
        if (b.signum() <= 0) return new Payoff(0, BigDecimal.ZERO, false);
        BigDecimal monthly = nz(annualRate).divide(BigDecimal.valueOf(12), MC);
        BigDecimal total = BigDecimal.ZERO;
        for (int month = 1; month <= MAX_MONTHS; month++) {
            BigDecimal interest = b.multiply(monthly, MC).multiply(IVA, MC).setScale(2, RoundingMode.HALF_UP);
            BigDecimal owed = b.add(interest);
            BigDecimal minimum = nz(productShare).multiply(owed, MC);
            if (bankingFloor) {
                minimum = CardMinimumPayment.revolvingMinimum(minimum, owed, interest, creditLimit);
            }
            minimum = minimum.max(new BigDecimal("1.00")).min(owed).setScale(2, RoundingMode.HALF_UP);
            if (minimum.compareTo(interest) <= 0 && minimum.compareTo(owed) < 0) {
                return new Payoff(MAX_MONTHS, total, true);   // the minimum does not even cover the interest
            }
            total = total.add(minimum);
            b = owed.subtract(minimum);
            if (b.compareTo(new BigDecimal("0.01")) < 0) {
                return new Payoff(month, total.setScale(2, RoundingMode.HALF_UP), false);
            }
        }
        return new Payoff(MAX_MONTHS, total.setScale(2, RoundingMode.HALF_UP), true);
    }

    /** The fixed monthly payment (interest with IVA) that pays {@code balance} off in {@code months}. */
    public static BigDecimal paymentToPayOffIn(BigDecimal balance, BigDecimal annualRate, int months) {
        BigDecimal b = nz(balance);
        if (b.signum() <= 0 || months <= 0) return BigDecimal.ZERO;
        BigDecimal r = nz(annualRate).divide(BigDecimal.valueOf(12), MC).multiply(IVA, MC);
        if (r.signum() == 0) return b.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        BigDecimal factor = BigDecimal.ONE.add(r).pow(-months, MC);
        return b.multiply(r, MC).divide(BigDecimal.ONE.subtract(factor), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
