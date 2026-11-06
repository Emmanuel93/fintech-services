package com.fintech.payments.domain;

import com.fintech.payments.domain.allocation.AllocationMode;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

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

    /** Where the money came from (see {@link FundingSource}). */
    @Column(name = "funding_source", nullable = false, updatable = false)
    private String fundingSource = FundingSource.DIRECT.name();

    /** The received credit this order applies part of; null for direct payments and balance applications. */
    @Column(name = "inbound_credit_id", updatable = false)
    private UUID inboundCreditId;

    /** Its place in the received credit's prelation (1 = first applied). */
    @Column(name = "allocation_seq", updatable = false)
    private Integer allocationSeq;

    /** REFERENCED, BY_RULE or CREDIT_BALANCE. */
    @Column(name = "allocation_mode", updatable = false)
    private String allocationMode;

    /** When the money arrived: the portfolio applies the payment on this date. */
    @Column(name = "received_at", updatable = false)
    private Instant receivedAt;

    /** The SPEI operation date: the day the bank books the money. */
    @Column(name = "operation_date", updatable = false)
    private LocalDate operationDate;

    @Column(name = "tracking_key", updatable = false)
    private String trackingKey;

    /** The distributor's client who paid, when he deposited with his own reference (BENEFICIARY). */
    @Column(name = "beneficiary_party_id", updatable = false)
    private UUID beneficiaryPartyId;

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
        o.createdAt       = BusinessClock.current().now();
        return o;
    }

    /**
     * The share of a received credit that goes to one loan, in the prelation's order. The external reference is the
     * credit and the share, so a redelivered credit creates no second order.
     */
    public static PaymentOrder forReceivedCredit(UUID creditAccountId, UUID obligorPartyId, BigDecimal amount,
                                                 UUID inboundCreditId, int allocationSeq, AllocationMode mode,
                                                 Instant receivedAt, LocalDate operationDate, String trackingKey,
                                                 long snapshotVersion, UUID beneficiaryPartyId) {
        PaymentOrder o = forReceivedCredit(creditAccountId, obligorPartyId, amount, inboundCreditId, allocationSeq, mode,
                receivedAt, operationDate, trackingKey, snapshotVersion);
        o.beneficiaryPartyId = beneficiaryPartyId;
        return o;
    }

    public static PaymentOrder forReceivedCredit(UUID creditAccountId, UUID obligorPartyId, BigDecimal amount,
                                                 UUID inboundCreditId, int allocationSeq, AllocationMode mode,
                                                 Instant receivedAt, LocalDate operationDate, String trackingKey,
                                                 long snapshotVersion) {
        PaymentOrder o = create(creditAccountId, obligorPartyId, amount, PaymentMethod.SPEI,
                "CREDIT:" + inboundCreditId + ":" + allocationSeq, snapshotVersion);
        o.fundingSource = FundingSource.SPEI_CREDIT.name();
        o.inboundCreditId = inboundCreditId;
        o.allocationSeq = allocationSeq;
        o.allocationMode = mode.name();
        o.receivedAt = receivedAt;
        o.operationDate = operationDate;
        o.trackingKey = trackingKey;
        return o;
    }

    /**
     * The payer's balance in favor applied to one of their loans. No money moves in the bank: it was already there.
     * {@code sourceKey} names the cause (the due installment and the day), so a redelivered event applies nothing twice.
     */
    public static PaymentOrder fromCreditBalance(UUID creditAccountId, UUID obligorPartyId, BigDecimal amount,
                                                 String sourceKey, long snapshotVersion) {
        PaymentOrder o = create(creditAccountId, obligorPartyId, amount, PaymentMethod.INTERNAL_TRANSFER,
                "BALANCE:" + sourceKey, snapshotVersion);
        o.fundingSource = FundingSource.CREDIT_BALANCE.name();
        o.allocationMode = AllocationMode.CREDIT_BALANCE.name();
        o.receivedAt = o.createdAt;
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
        this.confirmedAt = BusinessClock.current().now();
    }

    public void reject(String reason) {
        if (PaymentStatus.CONFIRMED.name().equals(status) ||
            PaymentStatus.REVERSED.name().equals(status)) {
            throw new InvalidPaymentStateException(
                    "Cannot reject payment in status " + status + " (orderId=" + paymentOrderId + ")");
        }
        this.status          = PaymentStatus.REJECTED.name();
        this.rejectionReason = reason;
        this.rejectedAt      = BusinessClock.current().now();
    }

    public void reverse(String reason) {
        if (!PaymentStatus.CONFIRMED.name().equals(status)) {
            throw new InvalidPaymentStateException(
                    "Can only reverse CONFIRMED payments, current status=" + status);
        }
        this.status        = PaymentStatus.REVERSED.name();
        this.reversalReason = reason;
        this.reversedAt    = BusinessClock.current().now();
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
    public FundingSource fundingSource()   { return FundingSource.valueOf(fundingSource); }
    public UUID getInboundCreditId()       { return inboundCreditId; }
    public Integer getAllocationSeq()      { return allocationSeq; }
    public String getAllocationMode()      { return allocationMode; }
    public Instant getReceivedAt()         { return receivedAt; }
    public LocalDate getOperationDate()    { return operationDate; }
    public String getTrackingKey()         { return trackingKey; }
    public UUID getBeneficiaryPartyId()    { return beneficiaryPartyId; }
}
