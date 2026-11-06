package com.fintech.commission.domain;

import com.fintech.shared.time.BusinessClock;
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
 * A lot: everything one beneficiary is owed for one product up to a period, paid with one SPEI. LB-01: CONFIRMED is
 * immutable.
 *
 * <p>It carries its taxes ({@link LotTaxes}): the commission and its IVA are owed to the beneficiary (the gross,
 * {@code totalAmount}); the SPEI pays the net, and what is withheld from a natural person is paid to the SAT on their
 * behalf. PENDING → SENT (the order was requested) → CONFIRMED (the bank settled it); an order that failed or came back
 * returns the lot to PENDING with the reason, and the next run requests it again.
 */
@Entity
@Table(name = "liquidation_batches", schema = "commission")
public class LiquidationBatch {

    @Id
    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "beneficiary_party_id", nullable = false, updatable = false)
    private UUID beneficiaryPartyId;

    @Column(name = "product_type", updatable = false, length = 40)
    private String productType;

    @Column(nullable = false, updatable = false)
    private String period;

    /** The gross: commission and IVA, what is owed to the beneficiary. */
    @Column(name = "total_amount", nullable = false, updatable = false)
    private BigDecimal totalAmount;

    @Column(name = "subtotal", updatable = false)
    private BigDecimal subtotal;

    @Column(name = "iva_amount", nullable = false, updatable = false)
    private BigDecimal ivaAmount;

    @Column(name = "isr_withheld", nullable = false, updatable = false)
    private BigDecimal isrWithheld;

    @Column(name = "iva_withheld", nullable = false, updatable = false)
    private BigDecimal ivaWithheld;

    /** What the SPEI pays. */
    @Column(name = "net_amount", updatable = false)
    private BigDecimal netAmount;

    @Column(name = "records_count", nullable = false, updatable = false)
    private int recordsCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LiquidationBatchStatus status;

    /** The STP tracking key of the order that paid it. */
    @Column(name = "payment_ref")
    private String paymentRef;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "payout_attempts", nullable = false)
    private int payoutAttempts;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LiquidationBatch() {}

    public static LiquidationBatch open(UUID beneficiaryPartyId, String productType, String period, LotTaxes taxes,
                                        int recordsCount) {
        LiquidationBatch b = new LiquidationBatch();
        b.batchId            = UUID.randomUUID();
        b.beneficiaryPartyId = beneficiaryPartyId;
        b.productType        = productType;
        b.period             = period;
        b.subtotal           = taxes.subtotal();
        b.ivaAmount          = taxes.iva();
        b.isrWithheld        = taxes.isrWithheld();
        b.ivaWithheld        = taxes.ivaWithheld();
        b.totalAmount        = taxes.gross();
        b.netAmount          = taxes.net();
        b.recordsCount       = recordsCount;
        b.status             = LiquidationBatchStatus.PENDING;
        b.createdAt          = BusinessClock.current().now();
        return b;
    }

    /** The SPEI was requested. Each attempt has its own number, which keys its order. */
    public int requestPayout() {
        if (status != LiquidationBatchStatus.PENDING) {
            throw new IllegalStateException("El lote " + batchId + " está en " + status + ": sólo se paga uno PENDING");
        }
        this.status        = LiquidationBatchStatus.SENT;
        this.failureReason = null;
        this.payoutAttempts++;
        return payoutAttempts;
    }

    /** The bank settled the order. Settling twice changes nothing. */
    public boolean confirmPaid(String trackingKey, Instant settledAt) {
        if (status == LiquidationBatchStatus.CONFIRMED) return false;
        this.status      = LiquidationBatchStatus.CONFIRMED;
        this.paymentRef  = trackingKey;
        this.processedAt = settledAt != null ? settledAt : BusinessClock.current().now();
        return true;
    }

    /** The order failed or came back: the lot waits for the next run. Only a lot in flight goes back. */
    public boolean payoutFailed(String reason) {
        if (status != LiquidationBatchStatus.SENT) return false;
        this.status        = LiquidationBatchStatus.PENDING;
        this.failureReason = reason;
        return true;
    }

    /** The reference that names the lot's order across services (the clearing item of 2103 in accounting). */
    public String itemRef() { return "LOTE:" + batchId; }

    public boolean isPending() { return status == LiquidationBatchStatus.PENDING; }

    public UUID getBatchId()                  { return batchId; }
    public UUID getBeneficiaryPartyId()       { return beneficiaryPartyId; }
    public String getProductType()            { return productType; }
    public String getPeriod()                 { return period; }
    public BigDecimal getTotalAmount()        { return totalAmount; }
    public BigDecimal getSubtotal()           { return subtotal; }
    public BigDecimal getIvaAmount()          { return ivaAmount; }
    public BigDecimal getIsrWithheld()        { return isrWithheld; }
    public BigDecimal getIvaWithheld()        { return ivaWithheld; }
    public BigDecimal getNetAmount()          { return netAmount; }
    public int getRecordsCount()              { return recordsCount; }
    public LiquidationBatchStatus getStatus() { return status; }
    public String getPaymentRef()             { return paymentRef; }
    public String getFailureReason()          { return failureReason; }
    public int getPayoutAttempts()            { return payoutAttempts; }
    public Instant getProcessedAt()           { return processedAt; }
    public Instant getCreatedAt()             { return createdAt; }
}
