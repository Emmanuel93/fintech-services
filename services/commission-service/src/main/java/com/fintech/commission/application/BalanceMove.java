package com.fintech.commission.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What commission reads of a portfolio balance update: the fact that moved the balances, the interest it collected,
 * the payment order it comes from and its portfolio day; and, on a distributor line whose commission is netted
 * against her statements, the commission the portfolio settled ({@code COMMISSION_NETTED}) or undid.
 *
 * @param commissionSettlement how the product settles the distributor's commission ({@code NETTED} or {@code PAYOUT});
 *                             null when it pays none, or on events published before the field existed
 * @param commission           on a netting or its reversal: the statement, the tier and the amount
 */
public record BalanceMove(String eventId, UUID creditAccountId, String triggerEvent, long balanceVersion,
                          BigDecimal accruedInterestBalance, BigDecimal interestDelta, String sourceEventId,
                          LocalDate valueDate, String commissionSettlement, Netting commission) {

    public static final String PAYMENT_APPLIED  = "PAYMENT_APPLIED";
    public static final String PAYMENT_RETURNED = "PAYMENT_RETURNED";
    public static final String COMMISSION_NETTED = "COMMISSION_NETTED";
    public static final String COMMISSION_NETTED_REVERSED = "COMMISSION_NETTED_REVERSED";
    public static final String NETTED = "NETTED";
    private static final String RETURN_PREFIX   = PAYMENT_RETURNED + ":";

    /** The commission the portfolio netted against one statement, as it published it. */
    public record Netting(int cycleNumber, LocalDate paymentDueDate, long daysLate, BigDecimal rate, BigDecimal onTimeRate,
                          BigDecimal basis, BigDecimal amount, String paymentOrder) {}

    /** A move as it was read before the distributor's commission travelled with it. */
    public BalanceMove(String eventId, UUID creditAccountId, String triggerEvent, long balanceVersion,
                       BigDecimal accruedInterestBalance, BigDecimal interestDelta, String sourceEventId, LocalDate valueDate) {
        this(eventId, creditAccountId, triggerEvent, balanceVersion, accruedInterestBalance, interestDelta, sourceEventId,
                valueDate, null, null);
    }

    public boolean isPayment() { return PAYMENT_APPLIED.equals(triggerEvent); }

    public boolean isReturn() { return PAYMENT_RETURNED.equals(triggerEvent); }

    public boolean isNetting() { return COMMISSION_NETTED.equals(triggerEvent); }

    public boolean isNettingReversal() { return COMMISSION_NETTED_REVERSED.equals(triggerEvent); }

    /** Whether the line's commission is netted against her statements: its payments accrue no share of the interest. */
    public boolean isNetted() { return NETTED.equalsIgnoreCase(commissionSettlement); }

    /** The payment order: a payment's own, or the one its return names. */
    public String paymentOrder() {
        if (sourceEventId == null || sourceEventId.isBlank()) return null;
        return sourceEventId.startsWith(RETURN_PREFIX) ? sourceEventId.substring(RETURN_PREFIX.length()) : sourceEventId;
    }
}
