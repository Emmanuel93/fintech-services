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
 * <p>Inputs: annualised nominal rate (e.g. 24.0 for 24%), opening fee rate as percentage.
 */
public final class CatCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
    private static final int SCALE = 4;

    private CatCalculator() {}

    /**
     * Calculates CAT for an installment (amortising) loan.
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

        if (termMonths <= 0 || principal == null || principal.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal monthlyRate = nominalRateAnnual
                .divide(BigDecimal.valueOf(100), MC)
                .divide(MONTHS_PER_YEAR, MC);

        BigDecimal openingFee = openingFeeRate == null ? BigDecimal.ZERO
                : principal.multiply(openingFeeRate.divide(BigDecimal.valueOf(100), MC), MC);

        // Monthly payment: PMT = P * r / (1 - (1+r)^-n)
        BigDecimal pmt = monthlyPayment(principal, monthlyRate, termMonths);

        // Net disbursement to client (principal minus opening fee collected up front)
        BigDecimal netDisbursed = principal.subtract(openingFee);

        // Find monthly IRR via bisection such that NPV(pmt, n, irr) = netDisbursed
        BigDecimal irrMonthly = bisectIrr(netDisbursed, pmt, termMonths);

        // CAT = ((1 + irrMonthly)^12 - 1) * 100
        double catDouble = (Math.pow(1 + irrMonthly.doubleValue(), 12) - 1) * 100;
        return BigDecimal.valueOf(catDouble).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * CAT for a revolving product ≈ nominal annual rate (no amortisation schedule).
     *
     * @param nominalRateAnnual annual nominal rate as percentage
     * @return CAT as percentage rounded to 2 decimal places
     */
    public static BigDecimal calculateRevolving(BigDecimal nominalRateAnnual) {
        if (nominalRateAnnual == null) return BigDecimal.ZERO;
        return nominalRateAnnual.setScale(2, RoundingMode.HALF_UP);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static BigDecimal monthlyPayment(BigDecimal principal, BigDecimal r, int n) {
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
        double hi = 1.0; // 100% monthly rate as upper bound
        for (int i = 0; i < 100; i++) {
            double mid = (lo + hi) / 2;
            double npv = npv(payment, n, mid);
            if (Math.abs(npv - pv) < 1e-6) break;
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
