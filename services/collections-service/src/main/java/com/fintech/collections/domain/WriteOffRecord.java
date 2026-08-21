package com.fintech.collections.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only. "Quita total" — unilateral, no debtor agreement required (unlike CollectionAgreement).
 * WO-03: one per creditAccountId — an account cannot be written off twice.
 */
@Entity
@Table(name = "write_off_records", schema = "collections")
public class WriteOffRecord {

    @Id
    @Column(name = "write_off_id", nullable = false, updatable = false)
    private UUID writeOffId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Column(name = "principal_written_off", nullable = false, updatable = false)
    private BigDecimal principalWrittenOff;

    @Column(name = "interest_written_off", nullable = false, updatable = false)
    private BigDecimal interestWrittenOff;

    @Column(name = "penalty_written_off", nullable = false, updatable = false)
    private BigDecimal penaltyWrittenOff;

    @Column(name = "total_written_off", nullable = false, updatable = false)
    private BigDecimal totalWrittenOff;

    @Column(name = "authorized_by", nullable = false, updatable = false)
    private String authorizedBy;

    @Column(name = "authorization_ref", nullable = false, updatable = false)
    private String authorizationRef;

    @Column(name = "write_off_date", nullable = false, updatable = false)
    private Instant writeOffDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private WriteOffReason reason;

    @Column(name = "bureau_reported", nullable = false)
    private boolean bureauReported;

    protected WriteOffRecord() {}

    public static WriteOffRecord create(UUID caseId, UUID creditAccountId, UUID obligorPartyId,
                                         BigDecimal principal, BigDecimal interest, BigDecimal penalty,
                                         String authorizedBy, String authorizationRef, WriteOffReason reason) {
        WriteOffRecord w = new WriteOffRecord();
        w.writeOffId           = UUID.randomUUID();
        w.caseId                = caseId;
        w.creditAccountId       = creditAccountId;
        w.obligorPartyId        = obligorPartyId;
        w.principalWrittenOff   = principal;
        w.interestWrittenOff    = interest;
        w.penaltyWrittenOff     = penalty;
        w.totalWrittenOff       = principal.add(interest).add(penalty);
        w.authorizedBy          = authorizedBy;
        w.authorizationRef      = authorizationRef;
        w.writeOffDate           = Instant.now();
        w.reason                = reason;
        w.bureauReported        = false;
        return w;
    }

    public void markBureauReported() { this.bureauReported = true; }

    public UUID getWriteOffId()               { return writeOffId; }
    public UUID getCaseId()                   { return caseId; }
    public UUID getCreditAccountId()          { return creditAccountId; }
    public UUID getObligorPartyId()           { return obligorPartyId; }
    public BigDecimal getPrincipalWrittenOff(){ return principalWrittenOff; }
    public BigDecimal getInterestWrittenOff() { return interestWrittenOff; }
    public BigDecimal getPenaltyWrittenOff()  { return penaltyWrittenOff; }
    public BigDecimal getTotalWrittenOff()    { return totalWrittenOff; }
    public String getAuthorizedBy()           { return authorizedBy; }
    public String getAuthorizationRef()       { return authorizationRef; }
    public Instant getWriteOffDate()          { return writeOffDate; }
    public WriteOffReason getReason()         { return reason; }
    public boolean isBureauReported()         { return bureauReported; }
}
