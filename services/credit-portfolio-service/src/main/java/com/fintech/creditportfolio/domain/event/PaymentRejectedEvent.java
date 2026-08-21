package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when payments.payment-applied is rejected (e.g., overpayment).
 * payments-service consumes this to transition its PaymentOrder to REJECTED.
 */
public class PaymentRejectedEvent {

    private final String eventId;
    private final Instant occurredOn;
    private final String sourceEventId;
    private final UUID creditAccountId;
    private final BigDecimal rejectedAmount;
    private final String reason;

    public PaymentRejectedEvent(String sourceEventId, UUID creditAccountId,
                                 BigDecimal rejectedAmount, String reason) {
        this.eventId        = UUID.randomUUID().toString();
        this.occurredOn     = Instant.now();
        this.sourceEventId  = sourceEventId;
        this.creditAccountId = creditAccountId;
        this.rejectedAmount = rejectedAmount;
        this.reason         = reason;
    }

    public String getEventId()           { return eventId; }
    public Instant getOccurredOn()       { return occurredOn; }
    public String getSourceEventId()     { return sourceEventId; }
    public UUID getCreditAccountId()     { return creditAccountId; }
    public BigDecimal getRejectedAmount(){ return rejectedAmount; }
    public String getReason()            { return reason; }
}
