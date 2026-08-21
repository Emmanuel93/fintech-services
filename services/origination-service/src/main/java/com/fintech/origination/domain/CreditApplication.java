package com.fintech.origination.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root for the underwriting subdomain (ADR-001).
 *
 * <p>Created when an onboarded {@link Prospect} selects a credit product. Progresses
 * through scoring, offer, contract and disbursement stages.
 *
 * <p>Invariants:
 * <ul>
 *   <li>OA-01: terminal status is immutable</li>
 *   <li>OA-03: one active application per (prospectId, productType) — partial unique index</li>
 *   <li>UW-05: REJECTED requires non-blank rejectionReason (CONDUSEF)</li>
 *   <li>OM-02: offeredAmount ≤ maxAmount from catalog</li>
 *   <li>OM-04: offer expires at validUntil; acceptance after expiry is rejected</li>
 *   <li>CM-04: contract terms are immutable after signing</li>
 *   <li>CM-06: CLABE must be valid before signing</li>
 * </ul>
 */
@Entity
@Table(name = "credit_applications", schema = "origination")
public class CreditApplication {

    @Id
    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "prospect_id", nullable = false, updatable = false)
    private UUID prospectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "prospect_type", nullable = false, updatable = false)
    private ProspectType prospectType;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, updatable = false)
    private ProductType productType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Column(name = "requested_amount")
    private BigDecimal requestedAmount;

    @Column(name = "requested_term")
    private Integer requestedTerm;

    /**
     * Channel-captured promoter/distributor attribution (channels.CustomerIntent.promoterCode) —
     * propagated through to CreditProductCreationRequested so Commission (T6) can attribute the
     * resulting credit to a beneficiary. Opaque here — origination never validates or resolves it.
     */
    @Column(name = "promoter_code")
    private String promoterCode;

    @Column(name = "score_request_id")
    private UUID scoreRequestId;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "decision")
    private String decision;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "approval_flow")
    private String approvalFlow;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "documents_deadline")
    private Instant documentsDeadline;

    @Column(name = "documents_note")
    private String documentsNote;

    @Embedded
    private CreditOffer offer;

    @Embedded
    private Contract contract;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreditApplication() {}

    /** Convenience overload for callers with no channel/promoter context (e.g. direct API start). */
    public static CreditApplication start(
            UUID prospectId,
            ProspectType prospectType,
            ProductType productType,
            BigDecimal requestedAmount,
            Integer requestedTerm) {
        return start(prospectId, prospectType, productType, requestedAmount, requestedTerm, null);
    }

    public static CreditApplication start(
            UUID prospectId,
            ProspectType prospectType,
            ProductType productType,
            BigDecimal requestedAmount,
            Integer requestedTerm,
            String promoterCode) {

        if (prospectId == null)   throw new IllegalArgumentException("prospectId is required");
        if (prospectType == null) throw new IllegalArgumentException("prospectType is required");
        if (productType == null)  throw new IllegalArgumentException("productType is required");
        if (requestedAmount != null && requestedAmount.signum() <= 0) {
            throw new IllegalArgumentException("requestedAmount must be positive");
        }
        if (requestedTerm != null && requestedTerm <= 0) {
            throw new IllegalArgumentException("requestedTerm must be positive");
        }

        Instant now = Instant.now();
        CreditApplication a = new CreditApplication();
        a.applicationId   = UUID.randomUUID();
        a.prospectId      = prospectId;
        a.prospectType    = prospectType;
        a.productType     = productType;
        a.status          = ApplicationStatus.PENDING_SCORING;
        a.requestedAmount = requestedAmount;
        a.requestedTerm   = requestedTerm;
        a.promoterCode    = promoterCode;
        a.createdAt       = now;
        a.updatedAt       = now;
        return a;
    }

    // ── Phase C — scoring decision ───────────────────────────────────────────

    public void markScoring(UUID scoreRequestId) {
        guardNotTerminal();
        this.scoreRequestId = scoreRequestId;
        this.status         = ApplicationStatus.SCORING;
        touch();
    }

    public void linkScoreEvaluation(UUID scoreRequestId) {
        this.scoreRequestId = scoreRequestId;
        touch();
    }

    /** BAJO → AUTO_APPROVED → APPROVED (non-terminal; offer follows). Sets approvalFlow=AUTOMATIC. */
    public void approve(String riskLevel, String decision) {
        guardNotTerminal();
        this.riskLevel    = riskLevel;
        this.decision     = decision;
        this.approvalFlow = ApprovalFlow.AUTOMATIC.name();
        this.decidedBy    = "SYSTEM";
        this.status       = ApplicationStatus.APPROVED;
        touch();
    }

    /** MANUAL scoring result → awaits underwriter (approvalFlow=MANUAL). */
    public void sendToManualReview(String riskLevel, String decision) {
        guardNotTerminal();
        this.riskLevel    = riskLevel;
        this.decision     = decision;
        this.approvalFlow = ApprovalFlow.MANUAL.name();
        this.status       = ApplicationStatus.UNDER_MANUAL_REVIEW;
        touch();
    }

    /** MANUAL scoring + high amount → awaits committee (approvalFlow=COMMITTEE). */
    public void sendToCommitteeReview(String riskLevel, String decision) {
        guardNotTerminal();
        this.riskLevel    = riskLevel;
        this.decision     = decision;
        this.approvalFlow = ApprovalFlow.COMMITTEE.name();
        this.status       = ApplicationStatus.COMMITTEE_REVIEW;
        touch();
    }

    /**
     * Underwriter or committee approves the application.
     * Transitions UNDER_MANUAL_REVIEW | COMMITTEE_REVIEW → APPROVED.
     */
    public void recordManualApproval(String decidedBy) {
        if (!status.awaitingHumanDecision()) {
            throw new IllegalStateException(
                    "Cannot approve: application is in status " + status
                    + " — expected UNDER_MANUAL_REVIEW or COMMITTEE_REVIEW");
        }
        this.decidedBy = decidedBy;
        this.decision  = "APPROVED";
        this.status    = ApplicationStatus.APPROVED;
        touch();
    }

    /**
     * Underwriter or committee rejects the application (UW-05: reason mandatory).
     * Transitions UNDER_MANUAL_REVIEW | COMMITTEE_REVIEW → REJECTED.
     */
    public void recordManualRejection(String decidedBy, String rejectionReason) {
        if (!status.awaitingHumanDecision()) {
            throw new IllegalStateException(
                    "Cannot reject: application is in status " + status
                    + " — expected UNDER_MANUAL_REVIEW or COMMITTEE_REVIEW");
        }
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new IllegalArgumentException("rejectionReason is mandatory for REJECTED (UW-05)");
        }
        this.decidedBy       = decidedBy;
        this.rejectionReason = rejectionReason;
        this.decision        = "REJECTED";
        this.rejectedAt      = Instant.now();
        this.status          = ApplicationStatus.REJECTED;
        touch();
    }

    /** ALTO → REJECTED by scoring engine (UW-05: reason mandatory). */
    public void reject(String rejectionReason, String riskLevel) {
        guardNotTerminal();
        if (rejectionReason == null || rejectionReason.isBlank()) {
            throw new IllegalArgumentException("rejectionReason is mandatory for REJECTED (UW-05)");
        }
        this.rejectionReason = rejectionReason;
        this.riskLevel       = riskLevel;
        this.decidedBy       = "SYSTEM";
        this.decision        = "REJECTED";
        this.rejectedAt      = Instant.now();
        this.status          = ApplicationStatus.REJECTED;
        touch();
    }

    public void fail(String reason) {
        guardNotTerminal();
        this.rejectionReason = reason;
        this.status          = ApplicationStatus.FAILED;
        touch();
    }

    public void cancel() {
        guardNotTerminal();
        this.status = ApplicationStatus.CANCELLED;
        touch();
    }

    // ── E6 — documentos pendientes ───────────────────────────────────────────

    /**
     * El analista/comité pide documentos adicionales a mitad de la revisión.
     * UNDER_MANUAL_REVIEW | COMMITTEE_REVIEW → PENDING_DOCUMENTS, con un plazo (TTL).
     */
    public void requestDocuments(String requestedBy, String note, Instant deadline) {
        if (!status.awaitingHumanDecision()) {
            throw new IllegalStateException(
                    "Cannot request documents: application is in status " + status
                    + " — expected UNDER_MANUAL_REVIEW or COMMITTEE_REVIEW");
        }
        this.status            = ApplicationStatus.PENDING_DOCUMENTS;
        this.decidedBy         = requestedBy;
        this.documentsNote     = note;
        this.documentsDeadline = deadline;
        touch();
    }

    /**
     * El sujeto entregó los documentos: vuelve a la mesa de revisión.
     * PENDING_DOCUMENTS → UNDER_MANUAL_REVIEW. Limpia el plazo.
     */
    public void resumeAfterDocuments() {
        if (status != ApplicationStatus.PENDING_DOCUMENTS) {
            throw new IllegalStateException(
                    "Cannot resume: application is in status " + status + " — expected PENDING_DOCUMENTS");
        }
        this.status            = ApplicationStatus.UNDER_MANUAL_REVIEW;
        this.documentsDeadline = null;
        touch();
    }

    /**
     * Venció el plazo sin documentos: la solicitud se cancela (abandono).
     * PENDING_DOCUMENTS → CANCELLED.
     */
    public void expireForMissingDocuments() {
        if (status != ApplicationStatus.PENDING_DOCUMENTS) {
            throw new IllegalStateException(
                    "Cannot expire documents: application is in status " + status
                    + " — expected PENDING_DOCUMENTS");
        }
        this.status          = ApplicationStatus.CANCELLED;
        this.rejectionReason = "Documentos no entregados dentro del plazo";
        touch();
    }

    // ── Phase E — offer management ───────────────────────────────────────────

    /** APPROVED → OFFER_PRESENTED. Embeds the pricing snapshot (OM-02). */
    public void presentOffer(CreditOffer offer) {
        if (status != ApplicationStatus.APPROVED) {
            throw new IllegalStateException(
                    "Cannot present offer in status " + status + " — expected APPROVED");
        }
        this.offer  = offer;
        this.status = ApplicationStatus.OFFER_PRESENTED;
        touch();
    }

    /** OFFER_PRESENTED → OFFER_ACCEPTED. Validates TTL (OM-04). */
    public void acceptOffer() {
        if (status != ApplicationStatus.OFFER_PRESENTED) {
            throw new IllegalStateException(
                    "Cannot accept offer in status " + status + " — expected OFFER_PRESENTED");
        }
        if (offer == null || offer.isExpired()) {
            throw new IllegalStateException("Offer has expired (OM-04)");
        }
        offer.markAccepted();
        this.status = ApplicationStatus.OFFER_ACCEPTED;
        touch();
    }

    /** OFFER_PRESENTED | OFFER_ACCEPTED → OFFER_REJECTED (terminal). */
    public void rejectOffer() {
        if (status != ApplicationStatus.OFFER_PRESENTED && status != ApplicationStatus.OFFER_ACCEPTED) {
            throw new IllegalStateException(
                    "Cannot reject offer in status " + status);
        }
        this.status = ApplicationStatus.OFFER_REJECTED;
        touch();
    }

    /** OFFER_PRESENTED → OFFER_EXPIRED (terminal, triggered by TTL job). */
    public void expireOffer() {
        if (status != ApplicationStatus.OFFER_PRESENTED) {
            throw new IllegalStateException(
                    "Cannot expire offer in status " + status);
        }
        this.status = ApplicationStatus.OFFER_EXPIRED;
        touch();
    }

    // ── Phase F — contract management ───────────────────────────────────────

    /** OFFER_ACCEPTED → PENDING_SIGNATURE. Attaches initial contract (no signature yet). */
    public void generateContract(Contract contract) {
        if (status != ApplicationStatus.OFFER_ACCEPTED) {
            throw new IllegalStateException(
                    "Cannot generate contract in status " + status + " — expected OFFER_ACCEPTED");
        }
        this.contract = contract;
        this.status   = ApplicationStatus.PENDING_SIGNATURE;
        touch();
    }

    /** PENDING_SIGNATURE → CONTRACT_SIGNED. Completes contract with signature proof (CM-04, CM-06). */
    public void signContract(String clabeAccount, String documentRef) {
        if (status != ApplicationStatus.PENDING_SIGNATURE) {
            throw new IllegalStateException(
                    "Cannot sign contract in status " + status + " — expected PENDING_SIGNATURE");
        }
        this.contract.complete(clabeAccount, documentRef, Instant.now());
        this.status = ApplicationStatus.CONTRACT_SIGNED;
        touch();
    }

    /** CONTRACT_SIGNED → DISBURSED (terminal). Triggered when credit-portfolio activates the account. */
    public void markDisbursed() {
        if (status != ApplicationStatus.CONTRACT_SIGNED) {
            throw new IllegalStateException(
                    "Cannot mark disbursed in status " + status + " — expected CONTRACT_SIGNED");
        }
        this.status = ApplicationStatus.DISBURSED;
        touch();
    }

    // ── Guard ────────────────────────────────────────────────────────────────

    private void guardNotTerminal() {
        if (status != null && status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot modify application in terminal status " + status);
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID getApplicationId()         { return applicationId; }
    public UUID getProspectId()            { return prospectId; }
    public ProspectType getProspectType()  { return prospectType; }
    public ProductType getProductType()    { return productType; }
    public ApplicationStatus getStatus()   { return status; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public Integer getRequestedTerm()      { return requestedTerm; }
    public String getPromoterCode()        { return promoterCode; }
    public UUID getScoreRequestId()        { return scoreRequestId; }
    public String getRiskLevel()           { return riskLevel; }
    public String getDecision()            { return decision; }
    public String getRejectionReason()     { return rejectionReason; }
    public String getApprovalFlow()        { return approvalFlow; }
    public String getDecidedBy()           { return decidedBy; }
    public Instant getRejectedAt()         { return rejectedAt; }
    public Instant getDocumentsDeadline()  { return documentsDeadline; }
    public String getDocumentsNote()       { return documentsNote; }
    public CreditOffer getOffer()          { return offer; }
    public Contract getContract()          { return contract; }
    public Instant getCreatedAt()          { return createdAt; }
    public Instant getUpdatedAt()          { return updatedAt; }
}
