package com.fintech.closing.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The rules an account cuts by: the product's billing cycle pinned at activation or, for accounts
 * opened before the product declared one, the service's own policy for its product type.
 *
 * @param cutoffRule           how the cutoff date is found
 * @param cutoffDay            the day for {@code DAY_OF_MONTH} (1–28) or {@code DAY_OF_WEEK} (1 = Monday … 7 = Sunday)
 * @param paymentDueOffsetDays calendar days from the cutoff to the payment due date, when there are no due days
 * @param graceDays            days after a due date before the moratorium starts (informational here)
 * @param minimumPaymentRate   share of the balance due as minimum payment; empty = no minimum
 * @param shift                what to do when a date falls on a non-business day
 * @param cutoffDays           {@code SEMI_MONTHLY}: the two days of the month it cuts on; null for the calendar fortnight
 * @param paymentDueDays       {@code SEMI_MONTHLY}: the day each cutoff's statement is paid by; null to pay
 *                             {@code paymentDueOffsetDays} after the cutoff
 */
public record BillingCycle(CutoffRule cutoffRule, Integer cutoffDay, int paymentDueOffsetDays, int graceDays,
                           BigDecimal minimumPaymentRate, NonBusinessDayShift shift,
                           List<Integer> cutoffDays, List<Integer> paymentDueDays) {

    /** A cycle without fixed cutoff days: every rule but {@code SEMI_MONTHLY}. */
    public BillingCycle(CutoffRule cutoffRule, Integer cutoffDay, int paymentDueOffsetDays, int graceDays,
                        BigDecimal minimumPaymentRate, NonBusinessDayShift shift) {
        this(cutoffRule, cutoffDay, paymentDueOffsetDays, graceDays, minimumPaymentRate, shift, null, null);
    }

    /** The policy expressed as a cycle: what every account used before the product owned the cycle. */
    public static BillingCycle fromPolicy(ClosePolicy policy) {
        return new BillingCycle(policy.cutoffRule(),
                policy.getCutoffDay() == null ? null : policy.getCutoffDay().intValue(),
                policy.getPaymentDueOffsetDays(), policy.getPaymentDueOffsetDays(),
                policy.minimumPaymentRate().orElse(null), policy.shift(),
                policy.cutoffDays(), policy.paymentDueDays());
    }

    /** Parses what the activation event carries; empty when it carries nothing usable. */
    public static Optional<BillingCycle> parse(String cutoffRule, Integer cutoffDay, Integer paymentDueOffsetDays,
                                               Integer graceDays, BigDecimal minimumPaymentRate, String shift) {
        return parse(cutoffRule, cutoffDay, paymentDueOffsetDays, graceDays, minimumPaymentRate, shift, null, null);
    }

    /** Parses what the activation event carries, with the fortnight's days; empty when it carries nothing usable. */
    public static Optional<BillingCycle> parse(String cutoffRule, Integer cutoffDay, Integer paymentDueOffsetDays,
                                               Integer graceDays, BigDecimal minimumPaymentRate, String shift,
                                               List<Integer> cutoffDays, List<Integer> paymentDueDays) {
        if (cutoffRule == null || cutoffRule.isBlank()) return Optional.empty();
        CutoffRule rule;
        try {
            rule = CutoffRule.valueOf(cutoffRule.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        NonBusinessDayShift s = NonBusinessDayShift.NEXT;
        if (shift != null && !shift.isBlank()) {
            try { s = NonBusinessDayShift.valueOf(shift.trim().toUpperCase()); } catch (IllegalArgumentException ignored) { }
        }
        return Optional.of(new BillingCycle(rule, cutoffDay,
                paymentDueOffsetDays == null ? 0 : paymentDueOffsetDays,
                graceDays == null ? 0 : graceDays, minimumPaymentRate, s,
                pair(cutoffDays), pair(paymentDueDays)));
    }

    public Optional<BigDecimal> minimumPayment() {
        return Optional.ofNullable(minimumPaymentRate);
    }

    /** The fortnight's calendar: its two cutoff days and, when the product fixes them, its two payment days. */
    public SemiMonthlyCalendar semiMonthly() {
        return SemiMonthlyCalendar.of(cutoffDays, paymentDueDays);
    }

    /** Two days, or nothing: a list of any other size says nothing usable. */
    private static List<Integer> pair(List<Integer> days) {
        return days != null && days.size() == 2 && days.stream().noneMatch(Objects::isNull) ? List.copyOf(days) : null;
    }
}
