package com.fintech.creditportfolio.domain.event;

import java.time.Instant;
import java.util.UUID;

public class DelinquencyStatusUpdatedEvent {

    private final UUID creditAccountId;
    private final UUID obligorPartyId;
    private final String contractNumber;
    private final int daysDelinquent;
    private final Instant occurredAt;

    public DelinquencyStatusUpdatedEvent(UUID creditAccountId, UUID obligorPartyId,
                                          String contractNumber, int daysDelinquent) {
        this.creditAccountId = creditAccountId;
        this.obligorPartyId  = obligorPartyId;
        this.contractNumber  = contractNumber;
        this.daysDelinquent  = daysDelinquent;
        this.occurredAt      = Instant.now();
    }

    public UUID getCreditAccountId() { return creditAccountId; }
    public UUID getObligorPartyId()  { return obligorPartyId; }
    public String getContractNumber(){ return contractNumber; }
    public int getDaysDelinquent()   { return daysDelinquent; }
    public Instant getOccurredAt()   { return occurredAt; }
}
