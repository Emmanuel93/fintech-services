package com.fintech.creditportfolio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import com.fintech.shared.time.BusinessClock;
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

    /**
     * La fecha valor: el día de negocio al que pertenece el movimiento. Casi siempre es el día en que
     * se aplicó; el devengo no — el del día D se aplica a las 00:05 de D+1 y es de D. El estado de
     * cuenta arma el periodo por esta fecha.
     */
    @Column(name = "value_date", nullable = false, updatable = false)
    private LocalDate valueDate;

    /** El cierre del día cuyo lote trajo este cargo; nulo para todo lo demás. */
    @Column(name = "day_close_date", updatable = false)
    private LocalDate dayCloseDate;

    /**
     * What the entry moved in each bucket, signed (a payment is negative). Measured where the mutation
     * happens; it is what lets a returned payment put back exactly what its payment had covered. Null in
     * entries written before the columns existed.
     */
    @Column(name = "principal_delta", updatable = false)
    private BigDecimal principalDelta;

    @Column(name = "interest_delta", updatable = false)
    private BigDecimal interestDelta;

    @Column(name = "iva_delta", updatable = false)
    private BigDecimal ivaDelta;

    @Column(name = "penalty_delta", updatable = false)
    private BigDecimal penaltyDelta;

    /** The distributor's client who paid it with his own reference; null for any other entry. */
    @Column(name = "beneficiary_party_id", updatable = false)
    private UUID beneficiaryPartyId;

    protected BalanceEvent() {}

    /** Un movimiento del día de hoy (en hora de negocio). */
    public static BalanceEvent record(UUID creditAccountId, String sourceEventId, String eventType,
                                      BigDecimal deltaAmount, CreditAccount account) {
        return record(creditAccountId, sourceEventId, eventType, deltaAmount, account, null);
    }

    /** @param valueDate el día al que pertenece; {@code null} = hoy en hora de negocio. */
    public static BalanceEvent record(UUID creditAccountId, String sourceEventId, String eventType,
                                      BigDecimal deltaAmount, CreditAccount account, LocalDate valueDate) {
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
        e.appliedAt       = BusinessClock.current().now();
        e.valueDate       = valueDate != null ? valueDate : BusinessClock.current().today();
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
    public LocalDate getValueDate()    { return valueDate; }
    public LocalDate getDayCloseDate() { return dayCloseDate; }

    /** Lo anota como parte del lote del cierre de ese día. */
    public BalanceEvent inDayClose(LocalDate businessDate) {
        this.dayCloseDate = businessDate;
        return this;
    }

    /** A payment a distributor's client made himself, with his own reference. */
    public BalanceEvent paidBy(UUID beneficiaryPartyId) {
        this.beneficiaryPartyId = beneficiaryPartyId;
        return this;
    }

    public UUID getBeneficiaryPartyId() { return beneficiaryPartyId; }

    /** Records what the entry moved in each bucket (signed). */
    public BalanceEvent withBreakdown(BigDecimal principal, BigDecimal interest, BigDecimal iva, BigDecimal penalty) {
        this.principalDelta = principal;
        this.interestDelta  = interest;
        this.ivaDelta       = iva;
        this.penaltyDelta   = penalty;
        return this;
    }

    public boolean hasBreakdown()          { return principalDelta != null; }
    public BigDecimal getPrincipalDelta()  { return principalDelta; }
    public BigDecimal getInterestDelta()   { return interestDelta; }
    public BigDecimal getIvaDelta()        { return ivaDelta; }
    public BigDecimal getPenaltyDelta()    { return penaltyDelta; }
}
