package com.fintech.payments.domain.credit;

import com.fintech.payments.domain.allocation.AllocationPlan;
import com.fintech.payments.domain.allocation.ReferenceStatus;
import com.fintech.shared.time.BusinessClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A credit received on a payer's CLABE, as payments applied it: how much went to loans and how much stayed as the
 * payer's balance in favor. Its lines ({@link CreditAllocation}) say where each peso went. Recorded once per credit.
 */
@Entity
@Table(schema = "payments", name = "received_credits")
public class ReceivedCredit {

    @Id
    @Column(name = "inbound_credit_id", nullable = false, updatable = false)
    private UUID inboundCreditId;

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "operation_date", nullable = false, updatable = false)
    private LocalDate operationDate;

    @Column(name = "tracking_key", nullable = false, updatable = false)
    private String trackingKey;

    @Column(name = "numeric_reference", updatable = false)
    private Long numericReference;

    @Column(name = "reference_status", nullable = false, updatable = false)
    private String referenceStatus;

    @Column(name = "to_loans", nullable = false, updatable = false)
    private BigDecimal toLoans;

    @Column(name = "to_credit_balance", nullable = false, updatable = false)
    private BigDecimal toCreditBalance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReceivedCredit() {
    }

    public static ReceivedCredit applied(UUID inboundCreditId, UUID partyId, BigDecimal amount, Instant receivedAt,
                                         LocalDate operationDate, String trackingKey, Long numericReference,
                                         AllocationPlan plan) {
        ReceivedCredit c = new ReceivedCredit();
        c.inboundCreditId = inboundCreditId;
        c.partyId = partyId;
        c.amount = amount;
        c.receivedAt = receivedAt;
        c.operationDate = operationDate;
        c.trackingKey = trackingKey;
        c.numericReference = numericReference;
        c.referenceStatus = plan.referenceStatus().name();
        c.toLoans = plan.toLoans();
        c.toCreditBalance = plan.toCreditBalance();
        c.createdAt = BusinessClock.current().now();
        return c;
    }

    public ReferenceStatus referenceStatus() { return ReferenceStatus.valueOf(referenceStatus); }
    public UUID getInboundCreditId() { return inboundCreditId; }
    public UUID getPartyId() { return partyId; }
    public BigDecimal getAmount() { return amount; }
    public Instant getReceivedAt() { return receivedAt; }
    public LocalDate getOperationDate() { return operationDate; }
    public String getTrackingKey() { return trackingKey; }
    public Long getNumericReference() { return numericReference; }
    public BigDecimal getToLoans() { return toLoans; }
    public BigDecimal getToCreditBalance() { return toCreditBalance; }
    public Instant getCreatedAt() { return createdAt; }
}
