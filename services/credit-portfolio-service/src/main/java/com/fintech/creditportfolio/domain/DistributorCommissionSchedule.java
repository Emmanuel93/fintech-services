package com.fintech.creditportfolio.domain;

import com.fintech.creditportfolio.domain.config.DistributorCommission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The distributor's commission on a placement, installment by installment, written on the plan when it is created.
 *
 * <p>The market's rule is a flat share of what she collects: 18 % of every installment (principal and interest), the
 * same every fortnight. It is paid out of the placement's interest, never out of its capital. On a French plan the
 * interest comes early and the commission is flat, so the interest the placement has paid always covers the commission
 * paid so far as long as the plan's whole interest covers the whole commission; this schedule guarantees that by
 * capping the commission at the plan's interest (below 12 fortnights at 95 % the share would exceed it: at 6, 18 % of
 * what is collected is 155 % of the interest), scaling every installment's commission alike so it stays flat.
 *
 * <p>The two numbers the statement shows come from here: the share of what she collects (the rate) and what that is
 * of the placement's interest ({@link #shareOfInterest}: 86 % at 12 fortnights, 51 % at 24).
 */
public final class DistributorCommissionSchedule {

    private static final int SCALE = 2;

    private DistributorCommissionSchedule() {}

    /**
     * Writes each installment's commission on the plan; the plan is returned for chaining.
     *
     * <p>Flat, but never ahead of the interest the placement has paid: a placement made the day before a cutoff pays
     * one day of interest on its first installment, less than the flat commission on it, so that installment carries
     * what its interest can and the rest moves to the next one (which carries its own and the remainder). The plan's
     * whole commission is the same; only the first fortnight of a short first period is smaller.
     */
    public static List<Installment> plan(List<Installment> schedule, DistributorCommission terms) {
        if (schedule == null || schedule.isEmpty() || terms == null || nz(terms.rate()).signum() == 0) return schedule;
        int n = schedule.size();
        BigDecimal[] target = new BigDecimal[n];
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal share = BigDecimal.ZERO;
        for (int k = 0; k < n; k++) {
            Installment i = schedule.get(k);
            target[k] = terms.shareOf(i.getPrincipalAmount(), i.getInterestAmount(), i.getTaxAmount());
            interest = interest.add(nz(i.getInterestAmount()));
            share = share.add(target[k]);
        }
        // Capped at the plan's interest: the commission is paid out of it and never out of the capital.
        BigDecimal factor = share.signum() > 0 && share.compareTo(interest) > 0
                ? interest.divide(share, 12, RoundingMode.HALF_UP) : BigDecimal.ONE;
        BigDecimal total = factor.compareTo(BigDecimal.ONE) < 0 ? interest : share;
        BigDecimal paidInterest = BigDecimal.ZERO, assigned = BigDecimal.ZERO, carry = BigDecimal.ZERO;
        for (int k = 0; k < n; k++) {
            Installment i = schedule.get(k);
            paidInterest = paidInterest.add(nz(i.getInterestAmount()));
            BigDecimal want = target[k].multiply(factor).setScale(SCALE, RoundingMode.HALF_UP).add(carry);
            // The last installment takes what is left so the plan's commission is exactly the total.
            if (k == n - 1) want = total.subtract(assigned).max(BigDecimal.ZERO);
            BigDecimal room = paidInterest.subtract(assigned).max(BigDecimal.ZERO);
            BigDecimal own = want.min(room).setScale(SCALE, RoundingMode.HALF_UP);
            carry = want.subtract(own);
            i.scheduleCommission(own);
            assigned = assigned.add(own);
        }
        return schedule;
    }

    /** What the plan's commission is of its interest: the second of the statement's two numbers (1 = all of it). */
    public static BigDecimal shareOfInterest(List<Installment> schedule) {
        BigDecimal interest = BigDecimal.ZERO, commission = BigDecimal.ZERO;
        for (Installment i : schedule) {
            interest = interest.add(nz(i.getInterestAmount()));
            commission = commission.add(nz(i.getScheduledCommission()));
        }
        return interest.signum() == 0 ? BigDecimal.ZERO : commission.divide(interest, 4, RoundingMode.HALF_UP);
    }

    /**
     * Whether, installment by installment, the interest paid so far covers the commission paid so far: the invariant
     * that keeps the commission out of the capital.
     */
    public static boolean interestCoversCommission(List<Installment> schedule) {
        BigDecimal interest = BigDecimal.ZERO, commission = BigDecimal.ZERO;
        for (Installment i : schedule) {
            interest = interest.add(nz(i.getInterestAmount()));
            commission = commission.add(nz(i.getScheduledCommission()));
            if (commission.compareTo(interest) > 0) return false;
        }
        return true;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
