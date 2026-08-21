package com.fintech.origination.domain.event;

import java.time.Instant;
import java.util.UUID;

public class ApplicationRejectedEvent {

    private final String eventId;
    private final Instant occurredOn;
    private final UUID applicationId;
    private final UUID prospectId;
    private final String productType;
    private final String approvalFlow;
    private final String decidedBy;
    private final String rejectionReason;

    public ApplicationRejectedEvent(UUID applicationId, UUID prospectId, String productType,
                                     String approvalFlow, String decidedBy, String rejectionReason) {
        this.eventId         = UUID.randomUUID().toString();
        this.occurredOn      = Instant.now();
        this.applicationId   = applicationId;
        this.prospectId      = prospectId;
        this.productType     = productType;
        this.approvalFlow    = approvalFlow;
        this.decidedBy       = decidedBy;
        this.rejectionReason = rejectionReason;
    }

    public String getEventId()          { return eventId; }
    public Instant getOccurredOn()      { return occurredOn; }
    public UUID getApplicationId()      { return applicationId; }
    public UUID getProspectId()         { return prospectId; }
    public String getProductType()      { return productType; }
    public String getApprovalFlow()     { return approvalFlow; }
    public String getDecidedBy()        { return decidedBy; }
    public String getRejectionReason()  { return rejectionReason; }
}
