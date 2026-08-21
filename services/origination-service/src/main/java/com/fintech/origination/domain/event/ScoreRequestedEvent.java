package com.fintech.origination.domain.event;

import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectType;
import com.fintech.shared.event.DomainEvent;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Emitted when a {@link com.fintech.origination.domain.CreditApplication} is started —
 * i.e. when an onboarded prospect selects a product. This is what triggers the scoring
 * DECISION ENGINE (as opposed to ProspectCreated, which only triggers the bureau prefetch).
 *
 * <p>Consumers:
 * <ul>
 *   <li>scoring-service → runs the decision engine for (prospectType, productType),
 *       reusing the prefetched bureau report (SO-02), and emits
 *       ScoreGenerated | ScoreRejected | ScoreFailed back to origination.</li>
 *   <li>audit → compliance logging</li>
 * </ul>
 */
public class ScoreRequestedEvent extends DomainEvent {

    private final UUID applicationId;
    private final UUID prospectId;
    private final ProspectType prospectType;
    private final ProductType productType;
    private final BigDecimal requestedAmount;
    private final Integer requestedTerm;

    public ScoreRequestedEvent(
            UUID applicationId,
            UUID prospectId,
            ProspectType prospectType,
            ProductType productType,
            BigDecimal requestedAmount,
            Integer requestedTerm,
            String correlationId) {
        super(correlationId);
        this.applicationId   = applicationId;
        this.prospectId      = prospectId;
        this.prospectType    = prospectType;
        this.productType     = productType;
        this.requestedAmount = requestedAmount;
        this.requestedTerm   = requestedTerm;
    }

    public UUID getApplicationId()        { return applicationId; }
    public UUID getProspectId()           { return prospectId; }
    public ProspectType getProspectType() { return prospectType; }
    public ProductType getProductType()   { return productType; }
    public BigDecimal getRequestedAmount(){ return requestedAmount; }
    public Integer getRequestedTerm()     { return requestedTerm; }
}
