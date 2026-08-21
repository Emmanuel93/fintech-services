package com.fintech.creditportfolio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit of every balance mutation (one row per applied inbound event).
 *
 * <p>{@code sourceEventId} is unique — it provides idempotency for at-least-once Kafka delivery:
 * a re-delivered charge/payment event is recognised and skipped.
 */
@Entity
@Table(
    name = "balance_events",
    schema = "credit_portfolio",
    uniqueConstraints = @UniqueConstraint(name = "uq_balance_event_source", columnNames = "source_event_id")
)
public class BalanceEvent {

    @Id
    @Column(name = "balance_event_id", nullable = false, updatable = false)
    private UUID balanceEventId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    /** Originating event id from charges/payments/collections — idempotency key. */
    @Column(name = "source_event_id", nullable = false, updatable = false)
    private String sourceEventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "delta_amount", nullable = false, updatable = false)
    private BigDecimal deltaAmount;

    // Snapshot of balances AFTER applying this event
    @Column(name = "principal_after", nullable = false, updatable = false)
    private BigDecimal principalAfter;

    @Column(name = "interest_after", nullable = false, updatable = false)
    private BigDecimal interestAfter;

    @Column(name = "penalty_after", nullable = false, updatable = false)
    private BigDecimal penaltyAfter;

    @Column(name = "available_after", updatable = false)
    private BigDecimal availableAfter;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;

    protected BalanceEvent() {}

    public static BalanceEvent record(UUID creditAccountId, String sourceEventId, String eventType,
                                      BigDecimal deltaAmount, CreditAccount account) {
        BalanceEvent e = new BalanceEvent();
        e.balanceEventId  = UUID.randomUUID();
        e.creditAccountId = creditAccountId;
        e.sourceEventId   = sourceEventId;
        e.eventType       = eventType;
        e.deltaAmount     = deltaAmount;
        e.principalAfter  = account.getPrincipalBalance();
        e.interestAfter   = account.getAccruedInterestBalance();
        e.penaltyAfter    = account.getPenaltyBalance();
        e.availableAfter  = account.getAvailableCredit();
        e.appliedAt       = Instant.now();
        return e;
    }

    public UUID getBalanceEventId()    { return balanceEventId; }
    public UUID getCreditAccountId()   { return creditAccountId; }
    public String getSourceEventId()   { return sourceEventId; }
    public String getEventType()       { return eventType; }
    public BigDecimal getDeltaAmount() { return deltaAmount; }
    public BigDecimal getPrincipalAfter() { return principalAfter; }
    public BigDecimal getInterestAfter()  { return interestAfter; }
    public BigDecimal getPenaltyAfter()   { return penaltyAfter; }
    public BigDecimal getAvailableAfter() { return availableAfter; }
    public Instant getAppliedAt()      { return appliedAt; }
}
