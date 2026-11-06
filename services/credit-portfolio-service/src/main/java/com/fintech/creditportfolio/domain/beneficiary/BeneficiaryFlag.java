package com.fintech.creditportfolio.domain.beneficiary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored mark on a client: today, a distributor's report. Marks are never deleted, they are cleared, with who and
 * why, so the history of what was said about a client stays readable.
 */
@Entity
@Table(name = "beneficiary_flags", schema = "credit_portfolio")
public class BeneficiaryFlag {

    @Id
    @Column(name = "flag_id", nullable = false, updatable = false)
    private UUID flagId;

    @Column(name = "beneficiary_party_id", nullable = false, updatable = false)
    private UUID beneficiaryPartyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 30)
    private FlagReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    private FlagSource source;

    @Column(name = "distributor_party_id")
    private UUID distributorPartyId;

    @Column(name = "credit_account_id")
    private UUID creditAccountId;

    /** The catalog reason of a report. */
    @Column(name = "detail", length = 40)
    private String detail;

    @Column(name = "comment", length = 1000)
    private String comment;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt;

    @Column(name = "raised_by", length = 120)
    private String raisedBy;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "cleared_by", length = 120)
    private String clearedBy;

    @Column(name = "clear_comment", length = 1000)
    private String clearComment;

    protected BeneficiaryFlag() {}

    public static BeneficiaryFlag report(UUID beneficiaryPartyId, FlagSource source, UUID distributorPartyId,
                                         UUID creditAccountId, ReportReason detail, String comment, String raisedBy,
                                         Instant now) {
        if (detail == null) throw new IllegalArgumentException("Un reporte lleva un motivo del catálogo");
        BeneficiaryFlag f = new BeneficiaryFlag();
        f.flagId = UUID.randomUUID();
        f.beneficiaryPartyId = beneficiaryPartyId;
        f.reason = FlagReason.DISTRIBUTOR_REPORT;
        f.source = source == null ? FlagSource.DISTRIBUTOR : source;
        f.distributorPartyId = distributorPartyId;
        f.creditAccountId = creditAccountId;
        f.detail = detail.name();
        f.comment = comment;
        f.raisedBy = raisedBy;
        f.raisedAt = now;
        return f;
    }

    public boolean isActive() {
        return clearedAt == null;
    }

    public void clear(String clearedBy, String comment, Instant now) {
        if (clearedAt != null) throw new IllegalStateException("La marca " + flagId + " ya estaba levantada");
        this.clearedAt = now;
        this.clearedBy = clearedBy;
        this.clearComment = comment;
    }

    public UUID getFlagId()               { return flagId; }
    public UUID getBeneficiaryPartyId()   { return beneficiaryPartyId; }
    public FlagReason getReason()         { return reason; }
    public FlagSource getSource()         { return source; }
    public UUID getDistributorPartyId()   { return distributorPartyId; }
    public UUID getCreditAccountId()      { return creditAccountId; }
    public String getDetail()             { return detail; }
    public String getComment()            { return comment; }
    public Instant getRaisedAt()          { return raisedAt; }
    public String getRaisedBy()           { return raisedBy; }
    public Instant getClearedAt()         { return clearedAt; }
    public String getClearedBy()          { return clearedBy; }
    public String getClearComment()       { return clearComment; }
}
