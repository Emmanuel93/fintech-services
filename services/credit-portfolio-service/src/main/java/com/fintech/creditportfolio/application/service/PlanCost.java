package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.domain.Installment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The CAT of a plan, as Banxico's Circular 21/2009 defines it.
 *
 * <p>The CAT is the annual rate {@code i} at which the payments are worth what was disbursed:
 * {@code A = Σ B_k / (1 + i)^(s_k)}. Each payment {@code B_k} is its principal and its ordinary interest; IVA does not
 * enter (4.2 i), nor late charges, since the client is supposed to pay on time (5.1 a). {@code s_k} is the time from
 * the disbursement to the payment in years (4.1), counted in uniform periods (5.1 f): a year has 24 fortnights, 26
 * fourteen-day periods, 12 months or 52 weeks. So the CAT is the plan's periodic rate of return compounded for a year,
 * {@code (1 + irr)^m − 1}.
 *
 * <p>A placement's first period is as long as the days from the placement to the first cutoff, and it pays interest
 * for those days only. Its payment is {@code firstPeriod} periods after the disbursement, and every later one a whole
 * period after the previous: counting that first payment a whole period away would make a placement made the day
 * before a cutoff look cheaper than it is (a $1,000 one at 6 fortnights read 96.7 % at 95 % a year). Measured so, every
 * placement at 95 % costs about {@code (1 + 0.95/24)^24 − 1 = 153.9 %}, the rate's CAT. A plan without interest (a
 * purchase at months without interest) costs nothing.
 */
public final class PlanCost {

    private static final int MAX_ITERATIONS = 200;
    private static final double TOLERANCE = 1e-12;

    private PlanCost() {}

    /** A plan whose first period is a whole one. */
    public static BigDecimal cat(BigDecimal disbursed, List<Installment> plan, int periodsPerYear) {
        return cat(disbursed, plan, periodsPerYear, BigDecimal.ONE);
    }

    /**
     * @param disbursed      what the client received at the start (a placement carries no fee to deduct)
     * @param plan           the plan's installments in order; their principal and interest are the payments
     * @param periodsPerYear 24 for the fortnight, 26, 12 or 52
     * @param firstPeriod    how much of a period passes before the first payment, in (0, 1]; null for a whole one
     * @return the CAT as a fraction with six decimals (1.538770 is 153.9 %); null when it cannot be computed
     */
    public static BigDecimal cat(BigDecimal disbursed, List<Installment> plan, int periodsPerYear, BigDecimal firstPeriod) {
        if (disbursed == null || disbursed.signum() <= 0 || plan == null || plan.isEmpty() || periodsPerYear <= 0) {
            return null;
        }
        double offset = firstPeriod == null || firstPeriod.signum() <= 0 || firstPeriod.compareTo(BigDecimal.ONE) > 0
                ? 1 : firstPeriod.doubleValue();
        double[] payments = new double[plan.size()];
        double total = 0;
        for (int k = 0; k < plan.size(); k++) {
            Installment i = plan.get(k);
            payments[k] = nz(i.getPrincipalAmount()).add(nz(i.getInterestAmount())).doubleValue();
            total += payments[k];
        }
        double amount = disbursed.doubleValue();
        if (total <= amount) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        double irr = periodicRate(amount, payments, offset);
        if (Double.isNaN(irr)) {
            return null;
        }
        double cat = Math.pow(1 + irr, periodsPerYear) - 1;
        return BigDecimal.valueOf(cat).setScale(6, RoundingMode.HALF_UP);
    }

    /** Installments a year for a payment frequency, as the amortization counts them. */
    public static int periodsPerYear(String paymentFrequency) {
        if (paymentFrequency == null) return 12;
        return switch (paymentFrequency.toUpperCase()) {
            case "WEEKLY" -> 52;
            case "BIWEEKLY" -> 26;
            case "SEMI_MONTHLY" -> 24;
            default -> 12;
        };
    }

    /**
     * The rate per period at which the payments are worth {@code amount}: Newton's method from the payments' own
     * average, falling back to bisection if it leaves the bracket. The present value falls as the rate rises, so the
     * root is unique and positive when the payments add up to more than the amount.
     */
    private static double periodicRate(double amount, double[] payments, double offset) {
        double low = 0, high = 1;
        while (presentValue(high, payments, offset) > amount && high < 1e6) {
            high *= 2;
        }
        double rate = (sum(payments) / amount - 1) / payments.length;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            double f = presentValue(rate, payments, offset) - amount;
            if (Math.abs(f) < TOLERANCE * amount) {
                return rate;
            }
            if (f > 0) low = Math.max(low, rate); else high = Math.min(high, rate);
            double derivative = derivative(rate, payments, offset);
            double next = derivative == 0 ? Double.NaN : rate - f / derivative;
            rate = Double.isNaN(next) || next <= low || next >= high ? (low + high) / 2 : next;
        }
        return rate;
    }

    /** Σ B_k / (1 + r)^(offset + k − 1): the first payment {@code offset} periods away, each later one a period after. */
    private static double presentValue(double rate, double[] payments, double offset) {
        double value = 0;
        double discount = Math.pow(1 + rate, -offset);
        for (double payment : payments) {
            value += payment * discount;
            discount /= 1 + rate;
        }
        return value;
    }

    /** d/dr of the present value: −Σ (offset + k − 1)·B_k / (1 + r)^(offset + k). */
    private static double derivative(double rate, double[] payments, double offset) {
        double value = 0;
        double discount = Math.pow(1 + rate, -offset - 1);
        for (int k = 0; k < payments.length; k++) {
            value -= (offset + k) * payments[k] * discount;
            discount /= 1 + rate;
        }
        return value;
    }

    private static double sum(double[] values) {
        double total = 0;
        for (double v : values) total += v;
        return total;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
