package com.fintech.stp.infrastructure.adapter.in.api.dto;

import com.fintech.stp.domain.collection.InboundCredit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A received credit for the back office: who paid, how much, both dates, and what was decided. */
public record InboundCreditResponse(UUID inboundCreditId, String trackingKey, BigDecimal amount, Instant receivedAt,
                                    LocalDate operationDate, String payerName, String payerAccountMasked,
                                    Long numericReference, String concept, String decision, Integer returnCause,
                                    String returnReason) {
    public static InboundCreditResponse from(InboundCredit c) {
        String account = c.getPayerAccount();
        String masked = account == null || account.length() < 4 ? null : "****" + account.substring(account.length() - 4);
        return new InboundCreditResponse(c.getInboundCreditId(), c.getTrackingKey(), c.getAmount(), c.getReceivedAt(),
                c.getOperationDate(), c.getPayerName(), masked, c.getNumericReference(), c.getConcept(),
                c.decision().name(), c.getReturnCause(), c.getReturnReason());
    }
}
