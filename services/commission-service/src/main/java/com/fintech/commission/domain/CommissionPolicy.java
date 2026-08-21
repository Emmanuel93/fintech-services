package com.fintech.commission.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Versioned commission rate per (productType, commissionType[, distributorPartyId]) — one ACTIVE at
 * a time (CP-01), same pattern as ProvisionPolicy (D9) and ScoringPolicy (D2). {@code rate} is a
 * fraction of the interest collected (for DISTRIBUTOR_INTEREST_SHARE) — captured by the commercial
 * team, never computed by a model (CP-02).
 */
@Entity
@Table(name = "commission_policies", schema = "commission")
public class CommissionPolicy {

    @Id
    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;

    @Column(name = "product_type", nullable = false, updatable = false)
    private String productType;

    /** null = default rate for the product; non-null = override for a specific distributor. */
    @Column(name = "distributor_party_id", updatable = false)
    private UUID distributorPartyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "commission_type", nullable = false, updatable = false)
    private CommissionType commissionType;

    @Column(nullable = false, updatable = false)
    private BigDecimal rate;

    @Column(nullable = false, updatable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommissionPolicy() {}

    public static CommissionPolicy create(String productType, UUID distributorPartyId,
                                           CommissionType commissionType, BigDecimal rate, int version) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new InvalidCommissionRecordStateException("rate must be between 0 and 1, was " + rate);
        }
        CommissionPolicy p = new CommissionPolicy();
        p.policyId            = UUID.randomUUID();
        p.productType          = productType;
        p.distributorPartyId   = distributorPartyId;
        p.commissionType       = commissionType;
        p.rate                 = rate;
        p.version              = version;
        p.status               = PolicyStatus.ACTIVE;
        p.createdAt             = Instant.now();
        return p;
    }

    public void deprecate() { this.status = PolicyStatus.DEPRECATED; }

    public boolean isActive() { return status == PolicyStatus.ACTIVE; }

    public UUID getPolicyId()             { return policyId; }
    public String getProductType()        { return productType; }
    public UUID getDistributorPartyId()   { return distributorPartyId; }
    public CommissionType getCommissionType() { return commissionType; }
    public BigDecimal getRate()           { return rate; }
    public int getVersion()               { return version; }
    public PolicyStatus getStatus()       { return status; }
    public Instant getCreatedAt()         { return createdAt; }
}
