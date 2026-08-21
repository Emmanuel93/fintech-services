package com.fintech.creditportfolio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dispositions", schema = "credit_portfolio")
public class Disposition {

    @Id
    @Column(name = "disposition_id", nullable = false, updatable = false)
    private UUID dispositionId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition_type", nullable = false, updatable = false)
    private DispositionType dispositionType;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    /** Beneficiary — only for THIRD_PARTY_CREDIT (DE-02). */
    @Column(name = "beneficiary_party_id")
    private UUID beneficiaryPartyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DispositionStatus status;

    /** SPEI/external reference number after COMPLETED. */
    @Column(name = "external_ref")
    private String externalRef;

    /** Idempotency key for wallet-initiated dispositions — null for the origination-time disposition. */
    @Column(name = "source_event_id")
    private String sourceEventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Disposition() {}

    public static Disposition create(UUID creditAccountId, DispositionType type,
                                      BigDecimal amount, UUID beneficiaryPartyId) {
        return create(creditAccountId, type, amount, beneficiaryPartyId, null);
    }

    public static Disposition create(UUID creditAccountId, DispositionType type,
                                      BigDecimal amount, UUID beneficiaryPartyId,
                                      String sourceEventId) {
        Disposition d = new Disposition();
        d.dispositionId    = UUID.randomUUID();
        d.creditAccountId  = creditAccountId;
        d.dispositionType  = type;
        d.amount           = amount;
        d.beneficiaryPartyId = beneficiaryPartyId;
        d.status           = DispositionStatus.PENDING;
        d.sourceEventId    = sourceEventId;
        d.createdAt        = Instant.now();
        return d;
    }

    public void markProcessing() {
        this.status = DispositionStatus.PROCESSING;
    }

    public void complete(String externalRef) {
        this.status      = DispositionStatus.COMPLETED;
        this.externalRef = externalRef;
        this.completedAt = Instant.now();
    }

    public void fail() {
        this.status = DispositionStatus.FAILED;
    }

    public UUID getDispositionId()      { return dispositionId; }
    public UUID getCreditAccountId()    { return creditAccountId; }
    public DispositionType getDispositionType() { return dispositionType; }
    public BigDecimal getAmount()       { return amount; }
    public UUID getBeneficiaryPartyId() { return beneficiaryPartyId; }
    public DispositionStatus getStatus(){ return status; }
    public String getExternalRef()      { return externalRef; }
    public String getSourceEventId()    { return sourceEventId; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getCompletedAt()     { return completedAt; }
}
