package com.fintech.commission.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Attribution of a credit to its distributor/promoter (beneficiary of the commission), projected
 * from {@code credit-portfolio.credit-account-activated.promoterCode} on activation.
 *
 * <p><strong>Known constraint:</strong> {@code promoterCode} is propagated opaquely as a string end
 * to end (channels → origination → credit-portfolio) — there is no promoter/distributor directory in
 * the system today that resolves a code to a Party. Commission requires {@code promoterCode} to
 * already be the beneficiary's Party UUID (as a string) to attribute the commission; if it isn't a
 * valid UUID, no assignment is created and the credit simply never generates commission (CM-07,
 * logged, not a hard failure). This mirrors how {@code Lead.promoterPartyId} is already a resolved
 * UUID elsewhere in the system — a proper promoter-code directory is future work.
 */
@Entity
@Table(name = "credit_promoter_assignments", schema = "commission")
public class CreditPromoterAssignment {

    @Id
    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "beneficiary_party_id", nullable = false, updatable = false)
    private UUID beneficiaryPartyId;

    @Column(name = "product_type", nullable = false, updatable = false)
    private String productType;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreditPromoterAssignment() {}

    public static CreditPromoterAssignment create(UUID creditAccountId, UUID beneficiaryPartyId, String productType) {
        CreditPromoterAssignment a = new CreditPromoterAssignment();
        a.creditAccountId    = creditAccountId;
        a.beneficiaryPartyId = beneficiaryPartyId;
        a.productType        = productType;
        a.active              = true;
        a.createdAt           = Instant.now();
        return a;
    }

    public UUID getCreditAccountId()    { return creditAccountId; }
    public UUID getBeneficiaryPartyId() { return beneficiaryPartyId; }
    public String getProductType()      { return productType; }
    public boolean isActive()           { return active; }
    public Instant getCreatedAt()       { return createdAt; }
}
