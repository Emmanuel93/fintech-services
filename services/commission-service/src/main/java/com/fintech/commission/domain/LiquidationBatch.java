package com.fintech.commission.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** LB-01: CONFIRMED is immutable. One SPEI per beneficiary per period instead of one per credit. */
@Entity
@Table(name = "liquidation_batches", schema = "commission")
public class LiquidationBatch {

    @Id
    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "beneficiary_party_id", nullable = false, updatable = false)
    private UUID beneficiaryPartyId;

    @Column(nullable = false, updatable = false)
    private String period;

    @Column(name = "total_amount", nullable = false, updatable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LiquidationBatchStatus status;

    @Column(name = "payment_ref")
    private String paymentRef;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LiquidationBatch() {}

    public static LiquidationBatch open(UUID beneficiaryPartyId, String period, BigDecimal totalAmount) {
        LiquidationBatch b = new LiquidationBatch();
        b.batchId            = UUID.randomUUID();
        b.beneficiaryPartyId = beneficiaryPartyId;
        b.period             = period;
        b.totalAmount        = totalAmount;
        b.status             = LiquidationBatchStatus.PENDING;
        b.createdAt          = Instant.now();
        return b;
    }

    public void markSent(String paymentRef) {
        this.status      = LiquidationBatchStatus.SENT;
        this.paymentRef  = paymentRef;
        this.processedAt = Instant.now();
    }

    public UUID getBatchId()             { return batchId; }
    public UUID getBeneficiaryPartyId()  { return beneficiaryPartyId; }
    public String getPeriod()            { return period; }
    public BigDecimal getTotalAmount()   { return totalAmount; }
    public LiquidationBatchStatus getStatus() { return status; }
    public String getPaymentRef()        { return paymentRef; }
    public Instant getProcessedAt()      { return processedAt; }
    public Instant getCreatedAt()        { return createdAt; }
}
