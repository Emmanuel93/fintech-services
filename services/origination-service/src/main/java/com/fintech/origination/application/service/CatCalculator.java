package com.fintech.origination.application.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Simplified CAT (Costo Anual Total) calculator.
 *
 * <p>Formula reference: BdM Circular 21/2009.
 * This implementation uses a bisection-based IRR to account for the opening fee
 * in addition to the nominal rate. The result is approximate — a full BdM-compliant
 * calculation requires the complete payment schedule with all fees and dates.
 *
 * <p>Inputs in percent: annualised nominal rate (24.0 for 24%), opening fee rate (1.0 for 1%). The catalog keeps
 * rates as fractions; OfferService converts them before calling.
 */
public final class CatCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int SCALE = 12;

    private CatCalculator() {}

    /**
     * Calculates CAT for an installment (amortising) loan paid monthly.
     *
     * @param principal        loan principal
     * @param nominalRateAnnual annual nominal rate as percentage (e.g. 24.0 = 24%)
     * @param termMonths       term in months
     * @param openingFeeRate   opening fee as percentage of principal (e.g. 3.0 = 3%)
     * @return CAT as percentage rounded to 2 decimal places
     */
    public static BigDecimal calculateInstallment(
            BigDecimal principal,
            BigDecimal nominalRateAnnual,
            int termMonths,
            BigDecimal openingFeeRate) {
        return calculateInstallment(principal, nominalRateAnnual, termMonths, openingFeeRate, 12);
    }

    /**
     * CAT of an installment loan paying {@code periodsPerYear} times a year (52 weekly, 26 biweekly, 12 monthly): the
     * effective annual rate at which the installments are worth what the client actually received (the principal
     * minus the opening fee), {@code (1 + irr)^periodsPerYear − 1}.
     *
     * @param nominalRateAnnual annual nominal rate as percentage (24.0 = 24 %)
     * @param terms             number of installments
     * @param openingFeeRate    opening fee as percentage of principal (1.0 = 1 %)
     */
    public static BigDecimal calculateInstallment(
            BigDecimal principal,
            BigDecimal nominalRateAnnual,
            int terms,
            BigDecimal openingFeeRate,
            int periodsPerYear) {

        if (terms <= 0 || periodsPerYear <= 0 || principal == null || principal.signum() <= 0
                || nominalRateAnnual == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal periodicRate = nominalRateAnnual
                .divide(BigDecimal.valueOf(100), MC)
                .divide(BigDecimal.valueOf(periodsPerYear), MC);

        BigDecimal openingFee = openingFeeRate == null ? BigDecimal.ZERO
                : principal.multiply(openingFeeRate.divide(BigDecimal.valueOf(100), MC), MC);

        // Installment: PMT = P * r / (1 - (1+r)^-n)
        BigDecimal pmt = payment(principal, periodicRate, terms);

        // Net disbursement to client (principal minus opening fee collected up front)
        BigDecimal netDisbursed = principal.subtract(openingFee);

        // Find the periodic IRR via bisection such that NPV(pmt, n, irr) = netDisbursed
        BigDecimal irr = bisectIrr(netDisbursed, pmt, terms);

        double cat = (Math.pow(1 + irr.doubleValue(), periodsPerYear) - 1) * 100;
        return BigDecimal.valueOf(cat).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * CAT of a revolving product without fees, paid monthly: {@code (1 + rate / 12)^12 − 1}. It used to return the
     * nominal rate itself, which understates the cost.
     *
     * @param nominalRateAnnual annual nominal rate as percentage
     * @return CAT as percentage rounded to 2 decimal places
     */
    public static BigDecimal calculateRevolving(BigDecimal nominalRateAnnual) {
        return calculateRevolving(nominalRateAnnual, 12);
    }

    /**
     * CAT of a revolving product without fees that is paid {@code periodsPerYear} times a year. Circular 21/2009
     * (5.4.1) supposes the whole line drawn at the start and drawn again after every payment, and (5.4.2) paid off at
     * the end of the third year: every period pays the line's interest, so the periodic rate of return is the nominal
     * rate per period and the CAT is {@code (1 + rate / m)^m − 1}. A distributor line paid by fortnights (24 a year) at
     * 95 % costs 153.9 %, not the 149.5 % of a monthly line: valuing it monthly understated it.
     *
     * @param nominalRateAnnual annual nominal rate as percentage
     * @param periodsPerYear    payments a year (24 for the fortnight; 5.1 f of the Circular counts the year so)
     * @return CAT as percentage rounded to 2 decimal places
     */
    public static BigDecimal calculateRevolving(BigDecimal nominalRateAnnual, int periodsPerYear) {
        if (nominalRateAnnual == null || periodsPerYear <= 0) return BigDecimal.ZERO;
        double periodic = nominalRateAnnual.doubleValue() / 100 / periodsPerYear;
        return BigDecimal.valueOf((Math.pow(1 + periodic, periodsPerYear) - 1) * 100).setScale(2, RoundingMode.HALF_UP);
    }

    /** A rate kept as a fraction (0.32), as this calculator's percentage (32); one already in percent is left as is. */
    public static BigDecimal percent(BigDecimal rate) {
        if (rate == null) return null;
        return rate.compareTo(BigDecimal.ONE) <= 0 ? rate.movePointRight(2) : rate;
    }

    /** Installments a year for a payment frequency, as the portfolio's amortization counts them. */
    public static int periodsPerYear(String frequency) {
        if (frequency == null) return 12;
        return switch (frequency.toUpperCase()) {
            case "WEEKLY" -> 52;
            case "BIWEEKLY" -> 26;
            case "SEMI_MONTHLY" -> 24;
            default -> 12;
        };
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static BigDecimal payment(BigDecimal principal, BigDecimal r, int n) {
        if (r.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(n), MC);
        }
        // P * r / (1 - (1+r)^-n)
        double rD = r.doubleValue();
        double pmtD = principal.doubleValue() * rD / (1 - Math.pow(1 + rD, -n));
        return BigDecimal.valueOf(pmtD);
    }

    /** Bisection to find monthly IRR: PV(pmt, n, irr) = pv0. */
    private static BigDecimal bisectIrr(BigDecimal pv0, BigDecimal pmt, int n) {
        double pv = pv0.doubleValue();
        double payment = pmt.doubleValue();
        double lo = 0.0;
        double hi = 1.0; // 100 % per period as upper bound
        for (int i = 0; i < 100; i++) {
            double mid = (lo + hi) / 2;
            double npv = npv(payment, n, mid);
            if (Math.abs(npv - pv) < 1e-9) break;
            if (npv > pv) lo = mid;
            else hi = mid;
        }
        return BigDecimal.valueOf((lo + hi) / 2).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static double npv(double pmt, int n, double r) {
        if (r == 0) return pmt * n;
        return pmt * (1 - Math.pow(1 + r, -n)) / r;
    }
}
