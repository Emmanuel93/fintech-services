package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a charge-applied event is rejected because the account is in a terminal state.
 * charges-service consumes this to reverse the ChargeRecord that was optimistically persisted.
 */
public class ChargeRejectedEvent {

    private final String eventId;
    private final Instant occurredOn;
    private final String sourceEventId;
    private final UUID creditAccountId;
    private final String chargeType;
    private final BigDecimal rejectedAmount;
    private final String reason;

    public ChargeRejectedEvent(String sourceEventId, UUID creditAccountId,
                                String chargeType, BigDecimal rejectedAmount, String reason) {
        this.eventId         = UUID.randomUUID().toString();
        this.occurredOn      = Instant.now();
        this.sourceEventId   = sourceEventId;
        this.creditAccountId = creditAccountId;
        this.chargeType      = chargeType;
        this.rejectedAmount  = rejectedAmount;
        this.reason          = reason;
    }

    public String getEventId()           { return eventId; }
    public Instant getOccurredOn()       { return occurredOn; }
    public String getSourceEventId()     { return sourceEventId; }
    public UUID getCreditAccountId()     { return creditAccountId; }
    public String getChargeType()        { return chargeType; }
    public BigDecimal getRejectedAmount(){ return rejectedAmount; }
    public String getReason()            { return reason; }
}
