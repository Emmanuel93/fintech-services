package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a wallet-initiated wallet.disposition-requested is rejected
 * (revalidation failure — insufficient credit, terminal/suspended account, or
 * single-disposition guard on non-revolving products).
 */
public class DispositionRejectedEvent {

    private final String eventId;
    private final Instant occurredOn;
    private final String sourceEventId;
    private final UUID creditAccountId;
    private final BigDecimal rejectedAmount;
    private final String reason;

    public DispositionRejectedEvent(String sourceEventId, UUID creditAccountId,
                                     BigDecimal rejectedAmount, String reason) {
        this.eventId         = UUID.randomUUID().toString();
        this.occurredOn      = Instant.now();
        this.sourceEventId   = sourceEventId;
        this.creditAccountId = creditAccountId;
        this.rejectedAmount  = rejectedAmount;
        this.reason          = reason;
    }

    public String getEventId()            { return eventId; }
    public Instant getOccurredOn()        { return occurredOn; }
    public String getSourceEventId()      { return sourceEventId; }
    public UUID getCreditAccountId()      { return creditAccountId; }
    public BigDecimal getRejectedAmount() { return rejectedAmount; }
    public String getReason()             { return reason; }
}
