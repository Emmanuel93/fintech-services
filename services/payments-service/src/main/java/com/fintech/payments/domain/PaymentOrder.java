package com.fintech.payments.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root for a payment submission.
 *
 * Lifecycle: PENDING → CONFIRMED (credit-portfolio applied it)
 *                    → REJECTED  (credit-portfolio rejected, or pre-validation failed)
 *            CONFIRMED → REVERSED (SPEI devolution or operational reversal)
 *
 * PY-01: externalRef is globally unique — idempotency key for SPEI/CoDi references.
 * PY-02: amount must be > 0.
 * PY-03: CONFIRMED → REVERSED only; terminal states are REJECTED and REVERSED.
 */
@Entity
@Table(schema = "payments", name = "payment_orders",
       uniqueConstraints = @UniqueConstraint(name = "payment_orders_ref_uq", columnNames = "external_ref"))
public class PaymentOrder {

    @Id
    @Column(name = "payment_order_id", nullable = false, updatable = false)
    private UUID paymentOrderId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_method", nullable = false)
    private String paymentMethod;

    /** External idempotency key: SPEI CLAVE_RASTREO, CoDi referenceId, or client-supplied UUID. */
    @Column(name = "external_ref", nullable = false, updatable = false)
    private String externalRef;

    @Column(name = "status", nullable = false)
    private String status;

    /** Balance version from the snapshot used for pre-validation. Sent in payment-applied event. */
    @Column(name = "snapshot_version", nullable = false)
    private long snapshotVersion;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "reversal_reason")
    private String reversalReason;

    /**
     * The original amount submitted by the customer (e.g., full SPEI transfer).
     * Equals {@code amount} when there is no overpayment.
     * Set by {@link #recordOverpayment} when excess handling is applied.
     */
    @Column(name = "requested_amount")
    private BigDecimal requestedAmount;

    /** Strategy applied to any excess when requestedAmount > amount. Null if no overpayment. */
    @Column(name = "overpayment_strategy")
    private String overpaymentStrategy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    protected PaymentOrder() {}

    public static PaymentOrder create(UUID creditAccountId, UUID obligorPartyId,
                                       BigDecimal amount, PaymentMethod method,
                                       String externalRef, long snapshotVersion) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidPaymentStateException("Payment amount must be positive");
        }
        PaymentOrder o = new PaymentOrder();
        o.paymentOrderId  = UUID.randomUUID();
        o.creditAccountId = creditAccountId;
        o.obligorPartyId  = obligorPartyId;
        o.amount          = amount;
        o.paymentMethod   = method.name();
        o.externalRef     = externalRef;
        o.status          = PaymentStatus.PENDING.name();
        o.snapshotVersion = snapshotVersion;
        o.requestedAmount = amount;   // default: same as applied; updated by recordOverpayment
        o.createdAt       = Instant.now();
        return o;
    }

    /**
     * Records that the applied amount differs from what the customer originally sent.
     * Called when overpayment is detected and a strategy is applied.
     *
     * @param originalAmount the full amount received from the external system (e.g., SPEI)
     * @param strategy       how the excess was handled
     */
    public void recordOverpayment(BigDecimal originalAmount, OverpaymentStrategy strategy) {
        this.requestedAmount     = originalAmount;
        this.overpaymentStrategy = strategy.name();
    }

    /** Amount above {@code amount} that was received but not applied. Zero if no overpayment. */
    public BigDecimal getExcessAmount() {
        if (requestedAmount == null || requestedAmount.compareTo(amount) <= 0) return BigDecimal.ZERO;
        return requestedAmount.subtract(amount);
    }

    public void confirm() {
        if (!PaymentStatus.PENDING.name().equals(status)) {
            throw new InvalidPaymentStateException(
                    "Cannot confirm payment in status " + status + " (orderId=" + paymentOrderId + ")");
        }
        this.status      = PaymentStatus.CONFIRMED.name();
        this.confirmedAt = Instant.now();
    }

    public void reject(String reason) {
        if (PaymentStatus.CONFIRMED.name().equals(status) ||
            PaymentStatus.REVERSED.name().equals(status)) {
            throw new InvalidPaymentStateException(
                    "Cannot reject payment in status " + status + " (orderId=" + paymentOrderId + ")");
        }
        this.status          = PaymentStatus.REJECTED.name();
        this.rejectionReason = reason;
        this.rejectedAt      = Instant.now();
    }

    public void reverse(String reason) {
        if (!PaymentStatus.CONFIRMED.name().equals(status)) {
            throw new InvalidPaymentStateException(
                    "Can only reverse CONFIRMED payments, current status=" + status);
        }
        this.status        = PaymentStatus.REVERSED.name();
        this.reversalReason = reason;
        this.reversedAt    = Instant.now();
    }

    public boolean isPending()   { return PaymentStatus.PENDING.name().equals(status); }
    public boolean isConfirmed() { return PaymentStatus.CONFIRMED.name().equals(status); }

    public UUID getPaymentOrderId()   { return paymentOrderId; }
    public UUID getCreditAccountId()  { return creditAccountId; }
    public UUID getObligorPartyId()   { return obligorPartyId; }
    public BigDecimal getAmount()     { return amount; }
    public String getPaymentMethod()  { return paymentMethod; }
    public String getExternalRef()    { return externalRef; }
    public String getStatus()         { return status; }
    public long getSnapshotVersion()  { return snapshotVersion; }
    public String getRejectionReason(){ return rejectionReason; }
    public String getReversalReason() { return reversalReason; }
    public Instant getCreatedAt()          { return createdAt; }
    public Instant getConfirmedAt()        { return confirmedAt; }
    public Instant getRejectedAt()         { return rejectedAt; }
    public Instant getReversedAt()         { return reversedAt; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public String getOverpaymentStrategy() { return overpaymentStrategy; }
}
