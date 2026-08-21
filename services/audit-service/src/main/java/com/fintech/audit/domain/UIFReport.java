package com.fintech.audit.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "audit", name = "uif_reports")
public class UIFReport {

    @Id
    private UUID reportId;

    @Column(nullable = false)
    private UUID partyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UIFReportType reportType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UIFReportStatus status;

    private String triggerEventType;
    private String triggerAggregateId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant submittedAt;

    protected UIFReport() {}

    public static UIFReport create(UUID partyId, UIFReportType reportType,
                                   String triggerEventType, String triggerAggregateId,
                                   String notes) {
        var r = new UIFReport();
        r.reportId           = UUID.randomUUID();
        r.partyId            = partyId;
        r.reportType         = reportType;
        r.status             = UIFReportStatus.PENDING;
        r.triggerEventType   = triggerEventType;
        r.triggerAggregateId = triggerAggregateId;
        r.notes              = notes;
        r.createdAt          = Instant.now();
        return r;
    }

    public void markSubmitted() {
        this.status      = UIFReportStatus.SUBMITTED;
        this.submittedAt = Instant.now();
    }

    public void close() {
        this.status = UIFReportStatus.CLOSED;
    }

    public UUID getReportId()             { return reportId; }
    public UUID getPartyId()              { return partyId; }
    public UIFReportType getReportType()  { return reportType; }
    public UIFReportStatus getStatus()    { return status; }
    public String getTriggerEventType()   { return triggerEventType; }
    public String getTriggerAggregateId(){ return triggerAggregateId; }
    public String getNotes()              { return notes; }
    public Instant getCreatedAt()         { return createdAt; }
    public Instant getSubmittedAt()       { return submittedAt; }
}
