package com.fintech.creditproduct.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How a product's accounts cut and when they must pay. It is product configuration, versioned with
 * the product: the closing service derives every account's cutoff calendar from it (together with
 * the payment frequency), charges takes the grace period from it, and the statement prints the dates
 * it produces. Nothing about billing periods is hard-coded downstream.
 *
 * @param cutoffRule           {@code INSTALLMENT_DUE_DATE} (cut on each installment's due date, from activation),
 *                             {@code CYCLE_FROM_ACTIVATION} (one period after activation, then every period),
 *                             {@code DAY_OF_MONTH} (a fixed day, monthly), {@code DAY_OF_WEEK} (a fixed weekday,
 *                             every week or fortnight by the payment frequency), {@code SEMI_MONTHLY} (two fixed days
 *                             of every month, {@code cutoffDays}) or {@code NONE}
 * @param cutoffDay            1–28 for {@code DAY_OF_MONTH}; 1 (Monday) to 7 (Sunday) for {@code DAY_OF_WEEK}; otherwise null
 * @param paymentDueOffsetDays calendar days from the cutoff to the payment due date; with {@code paymentDueDays} it
 *                             is not used
 * @param graceDays            days after a due date before the moratorium starts
 * @param minimumPaymentRate   share of the balance due as minimum payment (revolving); null = no minimum
 * @param nonBusinessDayShift  {@code NEXT}, {@code PREV} or {@code NONE} when a date falls on a non-business day
 * @param cutoffDays           {@code SEMI_MONTHLY} only: the two days of the month it cuts on, ascending; a day past
 *                             the month's end is its last day ({@code [15, 31]} is the calendar fortnight)
 * @param paymentDueDays       {@code SEMI_MONTHLY} only, optional: the day of the month each cutoff's statement must
 *                             be paid by, in the order of {@code cutoffDays}; the first such day after the cutoff,
 *                             this month or the next
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BillingCycle(
        String cutoffRule,
        Integer cutoffDay,
        int paymentDueOffsetDays,
        int graceDays,
        BigDecimal minimumPaymentRate,
        String nonBusinessDayShift,
        /**
         * How ordinary interest is charged. {@code DAILY}: every day on the outstanding principal (a card, a line used
         * for oneself, a loan). {@code PER_PERIOD}: the interest each plan sets for its period, charged the day the
         * period falls due and not before (a distributor's placements, each with its own amortization plan).
         * Absent in cycles published before the field existed: those are {@code DAILY}.
         */
        String interestAccrual,
        List<Integer> cutoffDays,
        List<Integer> paymentDueDays
) {

    public static final String DAILY = "DAILY";
    public static final String PER_PERIOD = "PER_PERIOD";

    /** The shortest period two cutoffs a month may leave, in the shortest February. */
    private static final int SHORTEST_FORTNIGHT = 13;

    /** The cycle as it was declared before the interest mode existed: interest is daily. */
    public BillingCycle(String cutoffRule, Integer cutoffDay, int paymentDueOffsetDays, int graceDays,
                        BigDecimal minimumPaymentRate, String nonBusinessDayShift) {
        this(cutoffRule, cutoffDay, paymentDueOffsetDays, graceDays, minimumPaymentRate, nonBusinessDayShift, DAILY);
    }

    /** A cycle without fixed cutoff days: every rule but {@code SEMI_MONTHLY}. */
    public BillingCycle(String cutoffRule, Integer cutoffDay, int paymentDueOffsetDays, int graceDays,
                        BigDecimal minimumPaymentRate, String nonBusinessDayShift, String interestAccrual) {
        this(cutoffRule, cutoffDay, paymentDueOffsetDays, graceDays, minimumPaymentRate, nonBusinessDayShift,
                interestAccrual, null, null);
    }

    /** The interest mode, {@code DAILY} when the cycle does not say. */
    public String effectiveInterestAccrual() {
        return interestAccrual == null || interestAccrual.isBlank() ? DAILY : interestAccrual;
    }

    public static final String INSTALLMENT_DUE_DATE = "INSTALLMENT_DUE_DATE";
    public static final String CYCLE_FROM_ACTIVATION = "CYCLE_FROM_ACTIVATION";
    public static final String DAY_OF_MONTH = "DAY_OF_MONTH";
    public static final String DAY_OF_WEEK = "DAY_OF_WEEK";
    public static final String SEMI_MONTHLY = "SEMI_MONTHLY";
    public static final String NONE = "NONE";

    /**
     * Each product type's cycle when its definition declares none. A distributor line cuts on the 6th and the 21st and
     * its statement is paid by the 18th and by the 3rd of the next month (the business's fortnight, as the reference
     * system runs it); the grace ends the day before the next cutoff. It has no minimum: each statement asks for the
     * fortnight's payments of its placements and the period's charges.
     */
    public static BillingCycle defaultFor(ProductType type) {
        return switch (type) {
            case PERSONAL_LOAN, PAYROLL_LOAN, MICRO_LOAN, SME_LOAN, GROUP_LOAN ->
                    new BillingCycle(INSTALLMENT_DUE_DATE, null, 3, 3, null, "NEXT");
            case DISTRIBUTOR_LINE ->
                    new BillingCycle(SEMI_MONTHLY, null, 0, 2, null, "NEXT", PER_PERIOD, List.of(6, 21), List.of(18, 3));
            case CREDIT_CARD, REVOLVING_LINE, BUSINESS_REVOLVING_LINE ->
                    new BillingCycle(CYCLE_FROM_ACTIVATION, null, 20, 3, new BigDecimal("0.0500"), "NEXT");
        };
    }

    /** Whether the fields make sense together; the reason when they do not. */
    public Optional<String> inconsistency() {
        if (cutoffRule == null) return Optional.of("cutoffRule is required");
        if (DAY_OF_MONTH.equals(cutoffRule) && (cutoffDay == null || cutoffDay < 1 || cutoffDay > 28)) {
            return Optional.of("DAY_OF_MONTH needs cutoffDay between 1 and 28");
        }
        if (DAY_OF_WEEK.equals(cutoffRule) && (cutoffDay == null || cutoffDay < 1 || cutoffDay > 7)) {
            return Optional.of("DAY_OF_WEEK needs cutoffDay between 1 (Monday) and 7 (Sunday)");
        }
        if (SEMI_MONTHLY.equals(cutoffRule)) {
            Optional<String> semiMonthly = semiMonthlyInconsistency();
            if (semiMonthly.isPresent()) return semiMonthly;
        } else if (cutoffDays != null || paymentDueDays != null) {
            return Optional.of("cutoffDays and paymentDueDays belong to SEMI_MONTHLY only");
        }
        if (paymentDueOffsetDays < 0 || graceDays < 0) return Optional.of("offsets cannot be negative");
        if (interestAccrual != null && !DAILY.equals(interestAccrual) && !PER_PERIOD.equals(interestAccrual)) {
            return Optional.of("interestAccrual must be DAILY or PER_PERIOD");
        }
        if (minimumPaymentRate != null && (minimumPaymentRate.signum() <= 0 || minimumPaymentRate.compareTo(BigDecimal.ONE) > 0)) {
            return Optional.of("minimumPaymentRate must be in (0, 1]");
        }
        return Optional.empty();
    }

    /**
     * Two cutoff days, ascending, that leave at least thirteen days between cutoffs in every month (the shortest
     * February), and a due date that always falls before the next cutoff: otherwise a distributor would be asked for two
     * fortnights at once.
     */
    private Optional<String> semiMonthlyInconsistency() {
        if (cutoffDay != null) return Optional.of("SEMI_MONTHLY cuts on cutoffDays: no cutoffDay");
        if (cutoffDays == null || cutoffDays.size() != 2 || cutoffDays.stream().anyMatch(Objects::isNull)) {
            return Optional.of("SEMI_MONTHLY needs two cutoffDays");
        }
        int first = cutoffDays.get(0);
        int second = cutoffDays.get(1);
        if (first < 1 || second > 31 || first >= second) {
            return Optional.of("SEMI_MONTHLY cutoffDays must be two days of the month, ascending, between 1 and 31");
        }
        for (int length = 28; length <= 31; length++) {
            int secondThatMonth = Math.min(second, length);
            if (secondThatMonth - first < SHORTEST_FORTNIGHT || length - secondThatMonth + first < SHORTEST_FORTNIGHT) {
                return Optional.of("SEMI_MONTHLY cutoffDays must leave at least " + SHORTEST_FORTNIGHT
                        + " days between cutoffs in every month");
            }
        }
        if (paymentDueDays != null && (paymentDueDays.size() != 2 || paymentDueDays.stream().anyMatch(Objects::isNull)
                || paymentDueDays.stream().anyMatch(d -> d < 1 || d > 31))) {
            return Optional.of("SEMI_MONTHLY paymentDueDays must be two days of the month between 1 and 31");
        }
        // Two years, one of them leap, walk every month length and every February.
        LocalDate cutoff = LocalDate.of(2027, 1, 1);
        for (int i = 0; i < 48; i++) {
            cutoff = nextCutoff(cutoff, first, second);
            LocalDate next = nextCutoff(cutoff, first, second);
            // The gaps above keep the first day inside every month, so the cutoff on it is the month's first one.
            LocalDate due = paymentDueDays == null
                    ? cutoff.plusDays(paymentDueOffsetDays)
                    : firstDayAfter(cutoff, paymentDueDays.get(cutoff.getDayOfMonth() == first ? 0 : 1));
            if (!due.isAfter(cutoff) || !due.isBefore(next)) {
                return Optional.of("SEMI_MONTHLY pays after its cutoff and before the next one: the statement of "
                        + cutoff + " would fall due on " + due + ", and the next cutoff is " + next);
            }
        }
        return Optional.empty();
    }

    /** The first of the two cutoff days strictly after the date. */
    private static LocalDate nextCutoff(LocalDate after, int first, int second) {
        LocalDate month = after.withDayOfMonth(1);
        for (int m = 0; m < 2; m++, month = month.plusMonths(1)) {
            for (int day : new int[]{first, second}) {
                LocalDate candidate = month.withDayOfMonth(Math.min(day, month.lengthOfMonth()));
                if (candidate.isAfter(after)) return candidate;
            }
        }
        throw new IllegalStateException("no cutoff after " + after);
    }

    /** The first date strictly after {@code after} that falls on {@code day} (or on its month's last day). */
    private static LocalDate firstDayAfter(LocalDate after, int day) {
        LocalDate same = after.withDayOfMonth(Math.min(day, after.lengthOfMonth()));
        if (same.isAfter(after)) return same;
        LocalDate next = after.plusMonths(1).withDayOfMonth(1);
        return next.withDayOfMonth(Math.min(day, next.lengthOfMonth()));
    }
}
