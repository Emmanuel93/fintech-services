package com.fintech.origination.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Se pidieron documentos adicionales para una solicitud en revisión. Notifications lo consume para
 * avisar al sujeto qué debe entregar y para cuándo (el plazo/TTL).
 */
public class DocumentsRequestedEvent {

    private final String eventId;
    private final Instant occurredOn;
    private final UUID applicationId;
    private final UUID prospectId;
    private final String productType;
    private final String requestedBy;
    private final String note;
    private final Instant deadline;
    private final String correlationId;

    public DocumentsRequestedEvent(UUID applicationId, UUID prospectId, String productType,
                                   String requestedBy, String note, Instant deadline, String correlationId) {
        this.eventId       = UUID.randomUUID().toString();
        this.occurredOn    = Instant.now();
        this.applicationId = applicationId;
        this.prospectId    = prospectId;
        this.productType   = productType;
        this.requestedBy   = requestedBy;
        this.note          = note;
        this.deadline      = deadline;
        this.correlationId = correlationId;
    }

    public String getEventId()       { return eventId; }
    public Instant getOccurredOn()   { return occurredOn; }
    public UUID getApplicationId()   { return applicationId; }
    public UUID getProspectId()      { return prospectId; }
    public String getProductType()   { return productType; }
    public String getRequestedBy()   { return requestedBy; }
    public String getNote()          { return note; }
    public Instant getDeadline()     { return deadline; }
    public String getCorrelationId() { return correlationId; }
}
