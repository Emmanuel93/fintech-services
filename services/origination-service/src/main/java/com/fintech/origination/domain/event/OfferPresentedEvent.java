package com.fintech.origination.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published when origination presents a priced offer to a prospect. */
public class OfferPresentedEvent {

    private final String eventId;
    private final Instant occurredOn;
    private final UUID applicationId;
    private final UUID prospectId;
    private final String productCode;
    private final BigDecimal offeredAmount;
    private final BigDecimal offeredLine;
    private final Integer offeredTerm;
    private final BigDecimal nominalRate;
    private final BigDecimal cat;
    private final Instant validUntil;

    public OfferPresentedEvent(
            UUID applicationId,
            UUID prospectId,
            String productCode,
            BigDecimal offeredAmount,
            BigDecimal offeredLine,
            Integer offeredTerm,
            BigDecimal nominalRate,
            BigDecimal cat,
            Instant validUntil) {
        this.eventId       = UUID.randomUUID().toString();
        this.occurredOn    = Instant.now();
        this.applicationId = applicationId;
        this.prospectId    = prospectId;
        this.productCode   = productCode;
        this.offeredAmount = offeredAmount;
        this.offeredLine   = offeredLine;
        this.offeredTerm   = offeredTerm;
        this.nominalRate   = nominalRate;
        this.cat           = cat;
        this.validUntil    = validUntil;
    }

    public String getEventId()            { return eventId; }
    public Instant getOccurredOn()        { return occurredOn; }
    public UUID getApplicationId()        { return applicationId; }
    public UUID getProspectId()           { return prospectId; }
    public String getProductCode()        { return productCode; }
    public BigDecimal getOfferedAmount()  { return offeredAmount; }
    public BigDecimal getOfferedLine()    { return offeredLine; }
    public Integer getOfferedTerm()       { return offeredTerm; }
    public BigDecimal getNominalRate()    { return nominalRate; }
    public BigDecimal getCat()            { return cat; }
    public Instant getValidUntil()        { return validUntil; }
}
