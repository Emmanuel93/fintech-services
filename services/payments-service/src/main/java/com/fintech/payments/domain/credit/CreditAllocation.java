package com.fintech.payments.domain.credit;

import com.fintech.payments.domain.allocation.AllocationLine;
import com.fintech.payments.domain.allocation.AllocationMode;
import com.fintech.shared.time.BusinessClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One line of a received credit: which loan (or the balance in favor) got how much, how, and by which payment order. */
@Entity
@Table(schema = "payments", name = "credit_allocations")
public class CreditAllocation {

    @Id
    @Column(name = "allocation_id", nullable = false, updatable = false)
    private UUID allocationId;

    @Column(name = "inbound_credit_id", nullable = false, updatable = false)
    private UUID inboundCreditId;

    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Column(name = "mode", nullable = false, updatable = false)
    private String mode;

    @Column(name = "credit_account_id", updatable = false)
    private UUID creditAccountId;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "payment_order_id", updatable = false)
    private UUID paymentOrderId;

    /** The distributor's client who paid, when he deposited with his own reference. */
    @Column(name = "beneficiary_party_id", updatable = false)
    private UUID beneficiaryPartyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreditAllocation() {
    }

    public static CreditAllocation of(UUID inboundCreditId, AllocationLine line, UUID paymentOrderId) {
        CreditAllocation a = new CreditAllocation();
        a.allocationId = UUID.randomUUID();
        a.inboundCreditId = inboundCreditId;
        a.seq = line.sequence();
        a.mode = line.mode().name();
        a.creditAccountId = line.creditAccountId();
        a.amount = line.amount();
        a.paymentOrderId = paymentOrderId;
        a.beneficiaryPartyId = line.beneficiaryPartyId();
        a.createdAt = BusinessClock.current().now();
        return a;
    }

    public AllocationMode mode() { return AllocationMode.valueOf(mode); }
    public UUID getAllocationId() { return allocationId; }
    public UUID getInboundCreditId() { return inboundCreditId; }
    public int getSeq() { return seq; }
    public UUID getCreditAccountId() { return creditAccountId; }
    public BigDecimal getAmount() { return amount; }
    public UUID getPaymentOrderId() { return paymentOrderId; }
    public UUID getBeneficiaryPartyId() { return beneficiaryPartyId; }
    public Instant getCreatedAt() { return createdAt; }
}
