package com.fintech.statement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lo que el corte sellado dice de la cuenta. Son las cifras oficiales del período: el estado de
 * cuenta las presenta, no las recalcula.
 */
public record CutoffFacts(String sourceEventId, UUID runId, UUID creditAccountId, UUID obligorPartyId,
                          String productType, String productBehavior, String holderKind, int cycleNumber,
                          LocalDate periodStart, LocalDate cutoffDate, LocalDate paymentDueDate,
                          BigDecimal balanceAtCutoff, BigDecimal principalAtCutoff, BigDecimal interestAtCutoff,
                          BigDecimal amountDue, BigDecimal minimumPayment, int daysDelinquent) {

    /** El primer día del período; si el corte no lo trae, un ciclo hacia atrás desde el corte. */
    public LocalDate effectivePeriodStart() {
        return periodStart != null ? periodStart : cutoffDate.minusMonths(1).plusDays(1);
    }

    public String effectiveHolderKind() {
        return holderKind != null ? holderKind : "CUSTOMER";
    }

    public String effectiveBehavior() {
        return productBehavior != null ? productBehavior : "INSTALLMENT";
    }
}
