package com.fintech.receipt.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What {@code closing.cutoff-closed} said about a line's cutoff: the period, the due date and the cycle. Kept with each
 * receipt, so a retry generates the same without receiving the event again.
 */
public record CutoffFacts(String sourceEventId, UUID runId, UUID creditAccountId, UUID obligorPartyId,
                          String productType, String productBehavior, String holderKind, int cycleNumber,
                          LocalDate periodStart, LocalDate cutoffDate, LocalDate paymentDueDate,
                          BigDecimal balanceAtCutoff, BigDecimal principalAtCutoff, BigDecimal interestAtCutoff,
                          BigDecimal amountDue, BigDecimal minimumPayment, int daysDelinquent) {

    public static final String DISTRIBUTOR_LINE = "DISTRIBUTOR_LINE";

    public LocalDate effectivePeriodStart() {
        return periodStart != null ? periodStart : cutoffDate.minusMonths(1).plusDays(1);
    }

    /** Only a distributor's line has clients to give receipts to. */
    public boolean paysADistributor() {
        return DISTRIBUTOR_LINE.equalsIgnoreCase(productType);
    }
}
