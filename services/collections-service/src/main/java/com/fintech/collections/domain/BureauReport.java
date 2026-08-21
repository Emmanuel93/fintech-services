package com.fintech.collections.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only. BR-01: one per sourceRecordId — the same write-off/quita is never reported twice.
 * BR-05: FAILED retries indefinitely — a regulatory obligation, not a best-effort notification.
 */
@Entity
@Table(name = "bureau_reports", schema = "collections")
public class BureauReport {

    @Id
    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private BureauEventType eventType;

    @Column(name = "source_record_id", nullable = false, updatable = false, unique = true)
    private UUID sourceRecordId;

    @Column(name = "amount_reported", nullable = false, updatable = false)
    private BigDecimal amountReported;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BureauReportStatus status;

    @Column(name = "bureau_reference")
    private String bureauReference;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BureauReport() {}

    public static BureauReport create(UUID creditAccountId, UUID obligorPartyId, BureauEventType eventType,
                                       UUID sourceRecordId, BigDecimal amountReported) {
        BureauReport r = new BureauReport();
        r.reportId         = UUID.randomUUID();
        r.creditAccountId   = creditAccountId;
        r.obligorPartyId    = obligorPartyId;
        r.eventType         = eventType;
        r.sourceRecordId    = sourceRecordId;
        r.amountReported    = amountReported;
        r.status            = BureauReportStatus.PENDING;
        r.createdAt         = Instant.now();
        return r;
    }

    public void markSubmitted(String bureauReference) {
        this.status          = BureauReportStatus.SUBMITTED;
        this.bureauReference = bureauReference;
        this.submittedAt     = Instant.now();
    }

    public void markFailed() {
        this.status = BureauReportStatus.FAILED;
    }

    public boolean isPending() { return status == BureauReportStatus.PENDING || status == BureauReportStatus.FAILED; }

    public UUID getReportId()          { return reportId; }
    public UUID getCreditAccountId()   { return creditAccountId; }
    public UUID getObligorPartyId()    { return obligorPartyId; }
    public BureauEventType getEventType() { return eventType; }
    public UUID getSourceRecordId()    { return sourceRecordId; }
    public BigDecimal getAmountReported() { return amountReported; }
    public BureauReportStatus getStatus() { return status; }
    public String getBureauReference() { return bureauReference; }
    public Instant getSubmittedAt()    { return submittedAt; }
    public Instant getCreatedAt()      { return createdAt; }
}
