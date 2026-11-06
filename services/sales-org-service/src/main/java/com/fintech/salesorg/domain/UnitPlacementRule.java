package com.fintech.salesorg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a unit's declared placement policy: the limits for one client type.
 *
 * <p>A unit declares at most one row per client type; what it does not declare is inherited. The policy as a whole
 * is {@link PlacementPolicy}; this is how it is stored.
 */
@Entity
@Table(name = "placement_policy_rules", schema = "sales_org")
public class UnitPlacementRule {

    @Id
    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, updatable = false, length = 20)
    private ClientType clientType;

    @Column(name = "max_loan_amount", precision = 14, scale = 2)
    private BigDecimal maxLoanAmount;

    @Column(name = "max_outstanding", precision = 14, scale = 2)
    private BigDecimal maxOutstanding;

    @Column(name = "max_active_loans")
    private Integer maxActiveLoans;

    @Column(name = "max_term")
    private Integer maxTerm;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UnitPlacementRule() {}

    public static UnitPlacementRule of(UUID unitId, ClientType type, ClientLimits limits, String updatedBy) {
        UnitPlacementRule r = new UnitPlacementRule();
        r.ruleId = UUID.randomUUID();
        r.unitId = unitId;
        r.clientType = type;
        r.updatedBy = updatedBy;
        r.updatedAt = Instant.now();
        r.apply(limits, updatedBy);
        return r;
    }

    public void apply(ClientLimits limits, String updatedBy) {
        this.maxLoanAmount  = limits.maxLoanAmount();
        this.maxOutstanding = limits.maxOutstanding();
        this.maxActiveLoans = limits.maxActiveLoans();
        this.maxTerm        = limits.maxTerm();
        this.updatedBy      = updatedBy;
        this.updatedAt      = Instant.now();
    }

    public ClientLimits limits() {
        return new ClientLimits(maxLoanAmount, maxOutstanding, maxActiveLoans, maxTerm);
    }

    public UUID getRuleId()          { return ruleId; }
    public UUID getUnitId()          { return unitId; }
    public ClientType getClientType(){ return clientType; }
    public String getUpdatedBy()     { return updatedBy; }
    public Instant getUpdatedAt()    { return updatedAt; }
}
