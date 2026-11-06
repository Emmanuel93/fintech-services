package com.fintech.creditportfolio.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

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
    /** The account's obligor (the distributor): what beneficiary matches the refused placement by. */
    private final UUID obligorPartyId;
    private final BigDecimal rejectedAmount;
    private final String reason;

    public DispositionRejectedEvent(String sourceEventId, UUID creditAccountId,
                                     BigDecimal rejectedAmount, String reason) {
        this(sourceEventId, creditAccountId, null, rejectedAmount, reason);
    }

    public DispositionRejectedEvent(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                     BigDecimal rejectedAmount, String reason) {
        this.eventId         = UUID.randomUUID().toString();
        this.occurredOn      = BusinessClock.current().now();
        this.sourceEventId   = sourceEventId;
        this.creditAccountId = creditAccountId;
        this.obligorPartyId  = obligorPartyId;
        this.rejectedAmount  = rejectedAmount;
        this.reason          = reason;
    }

    public String getEventId()            { return eventId; }
    public Instant getOccurredOn()        { return occurredOn; }
    public String getSourceEventId()      { return sourceEventId; }
    public UUID getCreditAccountId()      { return creditAccountId; }
    public UUID getObligorPartyId()       { return obligorPartyId; }
    public BigDecimal getRejectedAmount() { return rejectedAmount; }
    public String getReason()             { return reason; }
}
