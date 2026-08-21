package com.fintech.collections.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root — one per creditAccountId currently in delinquency (CC-01: only one
 * OPEN/MANAGED/LEGAL case at a time). Never modifies balances — only decides collections
 * strategy and requests write-offs/agreements that credit-portfolio applies.
 */
@Entity
@Table(name = "collection_cases", schema = "collections")
public class CollectionCase {

    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Column(name = "product_type", nullable = false, updatable = false)
    private String productType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_bucket", nullable = false)
    private DelinquencyBucket currentBucket;

    @Column(name = "days_delinquent", nullable = false)
    private int daysDelinquent;

    @Column(name = "total_debt", nullable = false)
    private BigDecimal totalDebt;

    @Column(name = "assigned_agent_id")
    private String assignedAgentId;

    @Column(name = "external_agency_id")
    private String externalAgencyId;

    @Column(name = "strategy")
    private String strategy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected CollectionCase() {}

    /** CM-01: opened the first time DelinquencyStatusUpdated arrives with bucket >= B1_30. */
    public static CollectionCase open(UUID creditAccountId, UUID obligorPartyId, String productType,
                                       int daysDelinquent, BigDecimal totalDebt, String strategy) {
        CollectionCase c = new CollectionCase();
        c.caseId          = UUID.randomUUID();
        c.creditAccountId = creditAccountId;
        c.obligorPartyId  = obligorPartyId;
        c.productType      = productType;
        c.status           = CaseStatus.OPEN;
        c.daysDelinquent   = daysDelinquent;
        c.currentBucket    = DelinquencyBucket.fromDaysDelinquent(daysDelinquent);
        c.totalDebt        = totalDebt;
        c.strategy         = strategy;
        c.openedAt         = Instant.now();
        return c;
    }

    /** CM-02: bucket changes -> re-evaluate. Moves OPEN -> MANAGED once an agent-level bucket is reached. */
    public void escalate(int newDaysDelinquent, BigDecimal newTotalDebt, String newStrategy) {
        guardNotTerminal();
        this.daysDelinquent = newDaysDelinquent;
        this.currentBucket  = DelinquencyBucket.fromDaysDelinquent(newDaysDelinquent);
        this.totalDebt      = newTotalDebt;
        this.strategy       = newStrategy;
        if (this.status == CaseStatus.OPEN && currentBucket != DelinquencyBucket.B1_30) {
            this.status = CaseStatus.MANAGED;
        }
    }

    public void assignAgent(String agentId) {
        guardNotTerminal();
        this.assignedAgentId = agentId;
    }

    /** CC-06: escalation to an external agency only from B91_120+. */
    public void escalateToAgency(String agencyId) {
        guardNotTerminal();
        if (!currentBucket.isEscalatable()) {
            throw new InvalidCaseStateException(
                    "External agency escalation requires bucket B91_120 or worse, current=" + currentBucket);
        }
        this.externalAgencyId = agencyId;
        this.status = CaseStatus.LEGAL;
    }

    /** CC-03/CM-05: DelinquencyCleared or ProductSettled -> CLOSED. */
    public void close() {
        if (status.isTerminal()) return; // idempotent
        this.status   = CaseStatus.CLOSED;
        this.closedAt = Instant.now();
    }

    /** CC-05: only after WriteOffExecuted. */
    public void markWrittenOff() {
        guardNotTerminal();
        this.status   = CaseStatus.WRITTEN_OFF;
        this.closedAt = Instant.now();
    }

    private void guardNotTerminal() {
        if (status.isTerminal()) {
            throw new InvalidCaseStateException("CollectionCase " + caseId + " is already " + status);
        }
    }

    public UUID getCaseId()            { return caseId; }
    public UUID getCreditAccountId()   { return creditAccountId; }
    public UUID getObligorPartyId()    { return obligorPartyId; }
    public String getProductType()     { return productType; }
    public CaseStatus getStatus()      { return status; }
    public DelinquencyBucket getCurrentBucket() { return currentBucket; }
    public int getDaysDelinquent()     { return daysDelinquent; }
    public BigDecimal getTotalDebt()   { return totalDebt; }
    public String getAssignedAgentId() { return assignedAgentId; }
    public String getExternalAgencyId(){ return externalAgencyId; }
    public String getStrategy()        { return strategy; }
    public Instant getOpenedAt()       { return openedAt; }
    public Instant getClosedAt()       { return closedAt; }
}
