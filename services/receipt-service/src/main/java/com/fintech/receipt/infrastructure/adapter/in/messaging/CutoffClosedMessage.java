package com.fintech.receipt.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.receipt.domain.CutoffFacts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** What receipt-service reads of {@code closing.cutoff-closed}: one message per line and cycle. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CutoffClosedMessage(String eventId, UUID runId, UUID creditAccountId, UUID obligorPartyId,
                                  String productType, String productBehavior, String holderKind, int cycleNumber,
                                  LocalDate periodStart, LocalDate cutoffDate, LocalDate paymentDueDate,
                                  BigDecimal balanceAtCutoff, BigDecimal principalAtCutoff,
                                  BigDecimal interestAtCutoff, BigDecimal amountDue, BigDecimal minimumPayment,
                                  int daysDelinquent) {

    public CutoffFacts toFacts() {
        return new CutoffFacts(eventId, runId, creditAccountId, obligorPartyId, productType, productBehavior,
                holderKind, cycleNumber, periodStart, cutoffDate, paymentDueDate, balanceAtCutoff,
                principalAtCutoff, interestAtCutoff, amountDue, minimumPayment, daysDelinquent);
    }
}
