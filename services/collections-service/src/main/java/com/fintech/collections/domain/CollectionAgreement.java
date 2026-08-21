package com.fintech.collections.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Bilateral agreement with the debtor: RESTRUCTURE (new terms, same obligation) or
 * QUITA_PARCIAL (partial forgiveness). Unlike WriteOffRecord (unilateral), this requires
 * debtor acceptance before it can be authorized and executed.
 *
 * AG-01: only one PROPOSED/ACCEPTED agreement per caseId at a time (enforced by the service).
 */
@Entity
@Table(name = "collection_agreements", schema = "collections")
public class CollectionAgreement {

    @Id
    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AgreementType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgreementStatus status;

    @Column(name = "original_debt", nullable = false, updatable = false)
    private BigDecimal originalDebt;

    @Column(name = "forgiven_amount")
    private BigDecimal forgivenAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_terms")
    private RestructureTerms newTerms;

    @Column(name = "authorized_by")
    private String authorizedBy;

    @Column(name = "authorization_ref")
    private String authorizationRef;

    @Column(name = "proposed_at", nullable = false, updatable = false)
    private Instant proposedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "bureau_reported", nullable = false)
    private boolean bureauReported;

    protected CollectionAgreement() {}

    public static CollectionAgreement proposeRestructure(UUID caseId, UUID creditAccountId, UUID obligorPartyId,
                                                           BigDecimal originalDebt, RestructureTerms newTerms) {
        CollectionAgreement a = base(caseId, creditAccountId, obligorPartyId, AgreementType.RESTRUCTURE, originalDebt);
        a.newTerms = newTerms;
        return a;
    }

    public static CollectionAgreement proposeQuitaParcial(UUID caseId, UUID creditAccountId, UUID obligorPartyId,
                                                            BigDecimal originalDebt, BigDecimal forgivenAmount,
                                                            BigDecimal maxForgivenessPct) {
        BigDecimal max = originalDebt.multiply(maxForgivenessPct);
        if (forgivenAmount.compareTo(max) > 0) {
            throw new ForgivenessLimitExceededException(forgivenAmount, max);
        }
        CollectionAgreement a = base(caseId, creditAccountId, obligorPartyId, AgreementType.QUITA_PARCIAL, originalDebt);
        a.forgivenAmount = forgivenAmount;
        return a;
    }

    private static CollectionAgreement base(UUID caseId, UUID creditAccountId, UUID obligorPartyId,
                                              AgreementType type, BigDecimal originalDebt) {
        CollectionAgreement a = new CollectionAgreement();
        a.agreementId    = UUID.randomUUID();
        a.caseId         = caseId;
        a.creditAccountId = creditAccountId;
        a.obligorPartyId  = obligorPartyId;
        a.type            = type;
        a.status          = AgreementStatus.PROPOSED;
        a.originalDebt    = originalDebt;
        a.proposedAt      = Instant.now();
        a.bureauReported  = false;
        return a;
    }

    /** The debtor accepts the proposal. */
    public void accept() {
        requireStatus(AgreementStatus.PROPOSED);
        this.status      = AgreementStatus.ACCEPTED;
        this.respondedAt = Instant.now();
    }

    public void reject() {
        requireStatus(AgreementStatus.PROPOSED);
        this.status      = AgreementStatus.REJECTED;
        this.respondedAt = Instant.now();
    }

    /** AG-05: no response within agreement_response_days (T5). */
    public void expire() {
        requireStatus(AgreementStatus.PROPOSED);
        this.status = AgreementStatus.EXPIRED;
    }

    /** AG-02: requires authorizationRef — no agreement executes without explicit approval. */
    public void execute(String authorizedBy, String authorizationRef) {
        requireStatus(AgreementStatus.ACCEPTED);
        this.status           = AgreementStatus.EXECUTED;
        this.authorizedBy      = authorizedBy;
        this.authorizationRef = authorizationRef;
        this.executedAt       = Instant.now();
    }

    public void markBureauReported() { this.bureauReported = true; }

    private void requireStatus(AgreementStatus expected) {
        if (status != expected) {
            throw new InvalidAgreementStateException(
                    "CollectionAgreement " + agreementId + " expected status=" + expected + " but was " + status);
        }
    }

    public boolean isRestructure()   { return type == AgreementType.RESTRUCTURE; }
    public boolean isQuitaParcial()  { return type == AgreementType.QUITA_PARCIAL; }
    public boolean requiresBureauReport() { return isQuitaParcial(); }

    public UUID getAgreementId()      { return agreementId; }
    public UUID getCaseId()           { return caseId; }
    public UUID getCreditAccountId()  { return creditAccountId; }
    public UUID getObligorPartyId()   { return obligorPartyId; }
    public AgreementType getType()    { return type; }
    public AgreementStatus getStatus(){ return status; }
    public BigDecimal getOriginalDebt(){ return originalDebt; }
    public BigDecimal getForgivenAmount() { return forgivenAmount; }
    public RestructureTerms getNewTerms() { return newTerms; }
    public String getAuthorizedBy()   { return authorizedBy; }
    public String getAuthorizationRef() { return authorizationRef; }
    public Instant getProposedAt()    { return proposedAt; }
    public Instant getRespondedAt()   { return respondedAt; }
    public Instant getExecutedAt()    { return executedAt; }
    public boolean isBureauReported() { return bureauReported; }
}
