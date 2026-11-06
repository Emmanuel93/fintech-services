package com.fintech.banking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One movement of a funding source, with the balances it left: what accounting books and what the bank must show. */
@Entity
@Table(name = "funding_movements", schema = "banking")
public class FundingMovement {

    public enum Type {
        CONTRIBUTION(true), DRAWDOWN(true), OPENING_FEE(false), INTEREST_ACCRUAL(false), INTEREST_PAYMENT(false),
        PRINCIPAL_REPAYMENT(false);

        private final boolean cashIn;

        Type(boolean cashIn) { this.cashIn = cashIn; }

        /** Money that enters the institution's bank account. */
        public boolean cashIn() { return cashIn; }

        /** Whether money moves at the bank: an accrual is a book entry only. */
        public boolean movesCash() { return this != INTEREST_ACCRUAL; }
    }

    @Id
    @Column(name = "funding_movement_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "funding_source_id", nullable = false, updatable = false)
    private UUID fundingSourceId;

    @Column(name = "movement_type", nullable = false, length = 20)
    private String movementType;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "accrued_from")
    private LocalDate accruedFrom;

    @Column(name = "accrued_through")
    private LocalDate accruedThrough;

    @Column(name = "reference", nullable = false, length = 120)
    private String reference;

    @Column(name = "contributed_after", nullable = false)
    private BigDecimal contributedAfter;

    @Column(name = "principal_after", nullable = false)
    private BigDecimal principalAfter;

    @Column(name = "interest_payable_after", nullable = false)
    private BigDecimal interestPayableAfter;

    @Column(name = "recorded_by", length = 120)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected FundingMovement() {}

    public static FundingMovement of(FundingSource source, Type type, BigDecimal amount, LocalDate valueDate,
                                     LocalDate accruedFrom, LocalDate accruedThrough, String reference,
                                     String recordedBy, Instant now) {
        FundingMovement m = new FundingMovement();
        m.id = UUID.randomUUID();
        m.fundingSourceId = source.getId();
        m.movementType = type.name();
        m.amount = amount;
        m.valueDate = valueDate;
        m.accruedFrom = accruedFrom;
        m.accruedThrough = accruedThrough;
        m.reference = reference;
        m.contributedAfter = source.getContributed();
        m.principalAfter = source.getPrincipal();
        m.interestPayableAfter = source.getInterestPayable();
        m.recordedBy = recordedBy;
        m.recordedAt = now;
        return m;
    }

    public UUID getId()                       { return id; }
    public UUID getFundingSourceId()          { return fundingSourceId; }
    public Type getType()                     { return Type.valueOf(movementType); }
    public BigDecimal getAmount()             { return amount; }
    public LocalDate getValueDate()           { return valueDate; }
    public LocalDate getAccruedFrom()         { return accruedFrom; }
    public LocalDate getAccruedThrough()      { return accruedThrough; }
    public String getReference()              { return reference; }
    public BigDecimal getContributedAfter()   { return contributedAfter; }
    public BigDecimal getPrincipalAfter()     { return principalAfter; }
    public BigDecimal getInterestPayableAfter() { return interestPayableAfter; }
    public String getRecordedBy()             { return recordedBy; }
    public Instant getRecordedAt()            { return recordedAt; }
}
