package com.fintech.origination.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Published when a prospect electronically signs the credit contract. */
public class ContractSignedEvent {

    private final String eventId;
    private final Instant occurredOn;
    private final UUID applicationId;
    private final UUID prospectId;
    private final String contractNumber;
    private final String signatureMethod;
    private final String clabeAccount;

    public ContractSignedEvent(
            UUID applicationId,
            UUID prospectId,
            String contractNumber,
            String signatureMethod,
            String clabeAccount) {
        this.eventId        = UUID.randomUUID().toString();
        this.occurredOn     = Instant.now();
        this.applicationId  = applicationId;
        this.prospectId     = prospectId;
        this.contractNumber = contractNumber;
        this.signatureMethod = signatureMethod;
        this.clabeAccount   = clabeAccount;
    }

    public String getEventId()          { return eventId; }
    public Instant getOccurredOn()      { return occurredOn; }
    public UUID getApplicationId()      { return applicationId; }
    public UUID getProspectId()         { return prospectId; }
    public String getContractNumber()   { return contractNumber; }
    public String getSignatureMethod()  { return signatureMethod; }
    public String getClabeAccount()     { return clabeAccount; }
}
