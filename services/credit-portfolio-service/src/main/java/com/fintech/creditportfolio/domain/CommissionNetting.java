package com.fintech.creditportfolio.domain;

import com.fintech.creditportfolio.domain.config.DistributorCommission;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What a distributor's payment settles of her commission: the statements it completes, oldest first, each one at the
 * tier of the day the money landed. The market's rule, as the portfolio applies it.
 *
 * <p>A statement is paid by her cash plus her commission on it. The commission on a statement is the flat share the
 * plans scheduled for the installments it billed ({@link DistributorCommissionSchedule}), scaled to the tier of the
 * days between the statement's due date and the payment's value date: whole by the due date, less each day after,
 * nothing past the ladder. It is granted when the statement gets completed and by the payment that completes it: a
 * partial payment earns nothing until the rest arrives, and the rest's day fixes the tier. What the payment leaves
 * after the statements issued before it lowers the balance with no commission, as any payment does.
 */
public final class CommissionNetting {

    private static final BigDecimal CENT = new BigDecimal("0.01");

    private CommissionNetting() {}

    /**
     * One statement's commission, settled by the payment.
     *
     * @param statement the statement completed
     * @param daysLate  days from its due date to the payment's value date (0 or less: on time)
     * @param rate      the tier's share
     * @param basis     what her clients paid that statement, as the share counts it
     * @param amount    the commission settled, to the cent
     */
    public record Grant(StatementCoverage.Obligation statement, long daysLate, BigDecimal rate, BigDecimal basis,
                        BigDecimal amount) {}

    /**
     * @param obligations      the account's statements (its cycle installments)
     * @param prior            the payments and nettings before this one, net of returns
     * @param cash             this payment
     * @param valueDate        the day the money landed
     * @param scheduledByCycle the on-time commission scheduled for each statement's installments
     * @param basisByCycle     what the clients pay each statement's installments, as the share counts it
     * @param nettedByCycle    the commission already netted against each statement
     */
    public static List<Grant> plan(List<StatementCoverage.Obligation> obligations, List<StatementCoverage.Payment> prior,
                                   BigDecimal cash, LocalDate valueDate, Map<Integer, BigDecimal> scheduledByCycle,
                                   Map<Integer, BigDecimal> basisByCycle, Map<Integer, BigDecimal> nettedByCycle,
                                   DistributorCommission terms) {
        List<Grant> grants = new ArrayList<>();
        if (terms == null || cash == null || cash.signum() <= 0 || obligations.isEmpty()) return grants;
        Map<java.util.UUID, BigDecimal> covered = StatementCoverage.cover(obligations, prior, null);
        List<StatementCoverage.Obligation> issued = obligations.stream()
                .filter(o -> o.cutoff() == null || o.cutoff().isBefore(valueDate))
                .sorted(Comparator.comparing((StatementCoverage.Obligation o) -> o.cutoff() == null ? LocalDate.MIN : o.cutoff())
                        .thenComparing(StatementCoverage.Obligation::due).thenComparingInt(StatementCoverage.Obligation::cycle))
                .toList();
        BigDecimal remaining = cash;
        for (StatementCoverage.Obligation o : issued) {
            if (remaining.signum() <= 0) break;
            BigDecimal missing = o.amount().subtract(covered.getOrDefault(o.id(), BigDecimal.ZERO));
            if (missing.compareTo(CENT) < 0) continue;
            BigDecimal onTime = nz(scheduledByCycle.get(o.cycle())).subtract(nz(nettedByCycle.get(o.cycle()))).max(BigDecimal.ZERO);
            long daysLate = ChronoUnit.DAYS.between(o.due(), valueDate);
            BigDecimal commission = terms.at(onTime, daysLate).min(missing);
            BigDecimal cashNeeded = missing.subtract(commission);
            if (remaining.add(CENT.divide(BigDecimal.TWO)).compareTo(cashNeeded) < 0) {
                // A partial payment: it covers what it covers and earns nothing until the statement is completed.
                break;
            }
            if (commission.compareTo(CENT) >= 0) {
                grants.add(new Grant(o, daysLate, terms.rateFor(daysLate), nz(basisByCycle.get(o.cycle())), commission));
            }
            remaining = remaining.subtract(cashNeeded.min(remaining));
        }
        return grants;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
