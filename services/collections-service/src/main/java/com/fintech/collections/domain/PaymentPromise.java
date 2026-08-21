package com.fintech.collections.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** PP-01: only one ACTIVE promise per caseId at a time (enforced by the service, via repository check). */
@Entity
@Table(name = "payment_promises", schema = "collections")
public class PaymentPromise {

    @Id
    @Column(name = "promise_id", nullable = false, updatable = false)
    private UUID promiseId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "promised_date", nullable = false, updatable = false)
    private LocalDate promisedDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PromiseStatus status;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private String recordedBy;

    @Column(name = "linked_payment_id")
    private UUID linkedPaymentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentPromise() {}

    public static PaymentPromise create(UUID caseId, BigDecimal amount, LocalDate promisedDate, String recordedBy) {
        if (promisedDate.isBefore(LocalDate.now())) {
            throw new InvalidCaseStateException("promisedDate must be >= today: " + promisedDate);
        }
        PaymentPromise p = new PaymentPromise();
        p.promiseId     = UUID.randomUUID();
        p.caseId        = caseId;
        p.amount        = amount;
        p.promisedDate  = promisedDate;
        p.status        = PromiseStatus.ACTIVE;
        p.recordedBy    = recordedBy;
        p.createdAt     = Instant.now();
        return p;
    }

    /** PP-05: PaymentApplied.amount >= promise.amount on promisedDate -> KEPT. */
    public void markKept(UUID paymentId) {
        requireActive();
        this.status          = PromiseStatus.KEPT;
        this.linkedPaymentId = paymentId;
    }

    /** PP-04: unmet promise at dawn of promisedDate -> BROKEN, by nightly job. */
    public void markBroken() {
        requireActive();
        this.status = PromiseStatus.BROKEN;
    }

    public void markExpired() {
        requireActive();
        this.status = PromiseStatus.EXPIRED;
    }

    private void requireActive() {
        if (status != PromiseStatus.ACTIVE) {
            throw new InvalidCaseStateException("PaymentPromise " + promiseId + " is already " + status);
        }
    }

    public boolean isActive() { return status == PromiseStatus.ACTIVE; }

    public UUID getPromiseId()        { return promiseId; }
    public UUID getCaseId()           { return caseId; }
    public BigDecimal getAmount()     { return amount; }
    public LocalDate getPromisedDate(){ return promisedDate; }
    public PromiseStatus getStatus()  { return status; }
    public String getRecordedBy()     { return recordedBy; }
    public UUID getLinkedPaymentId()  { return linkedPaymentId; }
    public Instant getCreatedAt()     { return createdAt; }
}
