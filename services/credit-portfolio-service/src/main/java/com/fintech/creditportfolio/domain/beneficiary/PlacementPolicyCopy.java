package com.fintech.creditportfolio.domain.beneficiary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The portfolio's copy of a unit's resolved placement policy ({@code sales-org.placement-policy-changed}).
 *
 * <p>One row per unit, already resolved by sales-org (inheritance folded in). A line finds the policy that applies to
 * it by its holder first (a DISTRIBUTOR node carries her {@code partyRef}; her own, stricter policy lives there) and
 * by its origin branch otherwise. Kept like the product configuration: a copy fed by events, read at authorization
 * without a call.
 */
@Entity
@Table(name = "placement_policies", schema = "credit_portfolio")
public class PlacementPolicyCopy {

    @Id
    @Column(name = "unit_code", nullable = false, updatable = false, length = 40)
    private String unitCode;

    @Column(name = "unit_id")
    private UUID unitId;

    @Column(name = "path")
    private String path;

    @Column(name = "level_code", length = 40)
    private String levelCode;

    @Column(name = "party_ref")
    private UUID partyRef;

    @Column(name = "configured", nullable = false)
    private boolean configured;

    @Column(name = "inherited_from", length = 40)
    private String inheritedFrom;

    @Column(name = "new_max_loan_amount", precision = 14, scale = 2) private BigDecimal newMaxLoanAmount;
    @Column(name = "new_max_outstanding", precision = 14, scale = 2) private BigDecimal newMaxOutstanding;
    @Column(name = "new_max_active_loans")                           private Integer newMaxActiveLoans;
    @Column(name = "new_max_term")                                   private Integer newMaxTerm;
    @Column(name = "rev_max_loan_amount", precision = 14, scale = 2) private BigDecimal revMaxLoanAmount;
    @Column(name = "rev_max_outstanding", precision = 14, scale = 2) private BigDecimal revMaxOutstanding;
    @Column(name = "rev_max_active_loans")                           private Integer revMaxActiveLoans;
    @Column(name = "rev_max_term")                                   private Integer revMaxTerm;

    @Column(name = "event_id", length = 60)
    private String eventId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlacementPolicyCopy() {}

    public static PlacementPolicyCopy of(String unitCode) {
        PlacementPolicyCopy c = new PlacementPolicyCopy();
        c.unitCode = unitCode;
        c.updatedAt = Instant.now();
        return c;
    }

    public PlacementPolicyCopy overwrite(UUID unitId, String path, String levelCode, UUID partyRef, boolean configured,
                                         String inheritedFrom, ClientLimits nuevo, ClientLimits revolvente,
                                         String eventId, Instant occurredOn) {
        this.unitId = unitId;
        this.path = path;
        this.levelCode = levelCode;
        this.partyRef = partyRef;
        this.configured = configured;
        this.inheritedFrom = inheritedFrom;
        ClientLimits n = nuevo == null ? ClientLimits.NONE : nuevo;
        ClientLimits r = revolvente == null ? ClientLimits.NONE : revolvente;
        this.newMaxLoanAmount = n.maxLoanAmount(); this.newMaxOutstanding = n.maxOutstanding();
        this.newMaxActiveLoans = n.maxActiveLoans(); this.newMaxTerm = n.maxTerm();
        this.revMaxLoanAmount = r.maxLoanAmount(); this.revMaxOutstanding = r.maxOutstanding();
        this.revMaxActiveLoans = r.maxActiveLoans(); this.revMaxTerm = r.maxTerm();
        this.eventId = eventId;
        this.updatedAt = occurredOn == null ? Instant.now() : occurredOn;
        return this;
    }

    public ClientLimits limitsFor(ClientType type) {
        return type == ClientType.REVOLVENTE
                ? new ClientLimits(revMaxLoanAmount, revMaxOutstanding, revMaxActiveLoans, revMaxTerm)
                : new ClientLimits(newMaxLoanAmount, newMaxOutstanding, newMaxActiveLoans, newMaxTerm);
    }

    public String getUnitCode()     { return unitCode; }
    public UUID getUnitId()         { return unitId; }
    public String getPath()         { return path; }
    public String getLevelCode()    { return levelCode; }
    public UUID getPartyRef()       { return partyRef; }
    public boolean isConfigured()   { return configured; }
    public String getInheritedFrom(){ return inheritedFrom; }
    public String getEventId()      { return eventId; }
    public Instant getUpdatedAt()   { return updatedAt; }
}
