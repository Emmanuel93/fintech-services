package com.fintech.commission.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Append-only-ish commission accrual. CR-01: {@code amount = basis * rate}. CR-02: rate is a
 * snapshot of the policy at accrual time — later policy changes never affect it retroactively.
 * CR-04: idempotent by {@code sourceEventId} (enforced by a unique DB constraint, not here).
 */
@Entity
@Table(name = "commission_records", schema = "commission")
public class CommissionRecord {

    @Id
    @Column(name = "commission_id", nullable = false, updatable = false)
    private UUID commissionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "commission_type", nullable = false, updatable = false)
    private CommissionType commissionType;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "beneficiary_party_id", nullable = false, updatable = false)
    private UUID beneficiaryPartyId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private String sourceEventId;

    @Column(nullable = false, updatable = false)
    private BigDecimal basis;

    @Column(nullable = false, updatable = false)
    private BigDecimal rate;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommissionRecordStatus status;

    @Column(nullable = false, updatable = false)
    private String period;

    @Column(name = "accrual_date", nullable = false, updatable = false)
    private Instant accrualDate;

    @Column(name = "liquidation_batch_id")
    private UUID liquidationBatchId;

    protected CommissionRecord() {}

    /** CR-01: amount = basis (interest collected) × rate (policy snapshot). */
    public static CommissionRecord accrue(CommissionType commissionType, UUID creditAccountId,
                                           UUID beneficiaryPartyId, String sourceEventId,
                                           BigDecimal basis, BigDecimal rate) {
        CommissionRecord r = new CommissionRecord();
        Instant now = Instant.now();
        r.commissionId       = UUID.randomUUID();
        r.commissionType     = commissionType;
        r.creditAccountId    = creditAccountId;
        r.beneficiaryPartyId = beneficiaryPartyId;
        r.sourceEventId      = sourceEventId;
        r.basis              = basis;
        r.rate               = rate;
        r.amount             = basis.multiply(rate);
        r.status             = CommissionRecordStatus.ACCRUED;
        r.period             = YearMonth.now().toString().replace("-", "");
        r.accrualDate        = now;
        return r;
    }

    /** CM-05: a returned payment "un-collects" the interest — reverse the commission it generated. */
    public void reverse() {
        if (status.isTerminalForEditing()) {
            throw new InvalidCommissionRecordStateException(
                    "CommissionRecord " + commissionId + " cannot be reversed — already " + status);
        }
        this.status = CommissionRecordStatus.REVERSED;
    }

    public void markLiquidated(UUID batchId) {
        if (status != CommissionRecordStatus.ACCRUED) {
            throw new InvalidCommissionRecordStateException(
                    "CommissionRecord " + commissionId + " cannot be liquidated — status " + status);
        }
        this.status             = CommissionRecordStatus.LIQUIDATED;
        this.liquidationBatchId = batchId;
    }

    public boolean isAccrued() { return status == CommissionRecordStatus.ACCRUED; }

    public UUID getCommissionId()          { return commissionId; }
    public CommissionType getCommissionType() { return commissionType; }
    public UUID getCreditAccountId()       { return creditAccountId; }
    public UUID getBeneficiaryPartyId()    { return beneficiaryPartyId; }
    public String getSourceEventId()       { return sourceEventId; }
    public BigDecimal getBasis()           { return basis; }
    public BigDecimal getRate()            { return rate; }
    public BigDecimal getAmount()          { return amount; }
    public CommissionRecordStatus getStatus() { return status; }
    public String getPeriod()              { return period; }
    public Instant getAccrualDate()        { return accrualDate; }
    public UUID getLiquidationBatchId()    { return liquidationBatchId; }
}
