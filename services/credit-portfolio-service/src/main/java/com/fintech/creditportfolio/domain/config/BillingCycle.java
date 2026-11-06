package com.fintech.creditportfolio.domain.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The product's billing cycle as the portfolio projects it from the catalog. The portfolio pins it to each account on
 * activation and publishes it, so closing and charges work from the cycle the account was opened with, whatever the
 * product says later. Of its rules the portfolio reads one: a fortnight's two cutoff days, which are the days its
 * placements' installments fall on.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BillingCycle(
        String cutoffRule,
        Integer cutoffDay,
        Integer paymentDueOffsetDays,
        Integer graceDays,
        BigDecimal minimumPaymentRate,
        String nonBusinessDayShift,
        /** DAILY or PER_PERIOD: how charges accrues the ordinary interest (see credit-product's BillingCycle). */
        String interestAccrual,
        /** SEMI_MONTHLY: the two days of the month it cuts on (the distributor line: 6 and 21). */
        List<Integer> cutoffDays,
        /** SEMI_MONTHLY: the day each cutoff's statement is paid by (18 and 3). */
        List<Integer> paymentDueDays
) {
    /** A cycle published before the interest mode existed. */
    public BillingCycle(String cutoffRule, Integer cutoffDay, Integer paymentDueOffsetDays, Integer graceDays,
                        BigDecimal minimumPaymentRate, String nonBusinessDayShift) {
        this(cutoffRule, cutoffDay, paymentDueOffsetDays, graceDays, minimumPaymentRate, nonBusinessDayShift, null);
    }

    /** A cycle without fixed cutoff days. */
    public BillingCycle(String cutoffRule, Integer cutoffDay, Integer paymentDueOffsetDays, Integer graceDays,
                        BigDecimal minimumPaymentRate, String nonBusinessDayShift, String interestAccrual) {
        this(cutoffRule, cutoffDay, paymentDueOffsetDays, graceDays, minimumPaymentRate, nonBusinessDayShift,
                interestAccrual, null, null);
    }

    /** The fortnight's calendar: its cutoff days, or the calendar fortnight (the 15th and the last day) without them. */
    public SemiMonthlyCalendar semiMonthly() {
        return SemiMonthlyCalendar.of(cutoffDays, paymentDueDays);
    }

    /** The fortnight of a product, the calendar fortnight when it has no cycle. */
    public static SemiMonthlyCalendar semiMonthlyOf(BillingCycle cycle) {
        return cycle == null ? SemiMonthlyCalendar.CALENDAR_FORTNIGHT : cycle.semiMonthly();
    }

    /**
     * The last day an installment's own date may fall on for the statement cut on {@code cutoff} to bill it. A cycle
     * that cuts twice a month stops the day before its next cutoff ({@link SemiMonthlyCalendar#lastBillableDay});
     * any other, or none, at the statement's payment due date, which never reaches its next cutoff.
     */
    public static LocalDate lastBillableDay(BillingCycle cycle, LocalDate cutoff, LocalDate paymentDueDate) {
        boolean fortnight = cycle != null && "SEMI_MONTHLY".equalsIgnoreCase(cycle.cutoffRule());
        return !fortnight || cutoff == null || paymentDueDate == null ? paymentDueDate
                : cycle.semiMonthly().lastBillableDay(cutoff, paymentDueDate);
    }
}
