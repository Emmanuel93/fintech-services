package com.fintech.creditportfolio.domain.beneficiary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A client's payment reference on a distributor's line: one per (line, client), for all his placements with her.
 *
 * <p>Seven digits with a Luhn check, drawn from the same sequence as the loans' references: a reference names a loan
 * or a client, never both. Printed on his receipt; a SPEI deposit to her CLABE with it lands on his installments.
 */
@Entity
@Table(name = "client_payment_references", schema = "credit_portfolio")
public class ClientPaymentReference {

    @Id
    @Column(name = "payment_reference", nullable = false, updatable = false)
    private Long paymentReference;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Column(name = "beneficiary_party_id", nullable = false, updatable = false)
    private UUID beneficiaryPartyId;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    protected ClientPaymentReference() {}

    public static ClientPaymentReference of(long paymentReference, UUID creditAccountId, UUID obligorPartyId,
                                            UUID beneficiaryPartyId, Instant assignedAt) {
        ClientPaymentReference r = new ClientPaymentReference();
        r.paymentReference = paymentReference;
        r.creditAccountId = creditAccountId;
        r.obligorPartyId = obligorPartyId;
        r.beneficiaryPartyId = beneficiaryPartyId;
        r.assignedAt = assignedAt;
        return r;
    }

    public Long getPaymentReference()   { return paymentReference; }
    public UUID getCreditAccountId()    { return creditAccountId; }
    public UUID getObligorPartyId()     { return obligorPartyId; }
    public UUID getBeneficiaryPartyId() { return beneficiaryPartyId; }
    public Instant getAssignedAt()      { return assignedAt; }
}
