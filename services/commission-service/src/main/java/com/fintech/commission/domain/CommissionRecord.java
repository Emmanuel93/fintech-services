package com.fintech.commission.domain;

import com.fintech.shared.time.BusinessClock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * An append-only commission ledger entry. CR-01: {@code amount = basis * rate}. CR-02: rate is a snapshot of the policy
 * at accrual time — later policy changes never affect it retroactively. CR-04: idempotent by {@code sourceEventId}
 * (enforced by a unique DB constraint, not here).
 *
 * <p>A returned payment does not edit the commission it produced: it appends a <b>reversal</b> (negative amounts,
 * {@code reversesCommissionId}) in proportion to the interest the return restores. While the original is unpaid both
 * net out in the same lot; once it was paid, the reversal is netted from the next lot of the same beneficiary and
 * product (a clawback). Before, a return reversed «the most recent accrual» of the loan, whatever payment that was.
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

    @Column(name = "product_type", updatable = false, length = 40)
    private String productType;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private String sourceEventId;

    /** The payment order the commission comes from: what a return names. */
    @Column(name = "payment_ref", updatable = false, length = 120)
    private String paymentRef;

    @Column(nullable = false, updatable = false)
    private BigDecimal basis;

    @Column(nullable = false, updatable = false)
    private BigDecimal rate;

    /** The commission, before IVA. Negative on a reversal. */
    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    /** The IVA the beneficiary invoices on top of the commission. Negative on a reversal. */
    @Column(name = "iva_amount", nullable = false, updatable = false)
    private BigDecimal ivaAmount;

    @Column(name = "reverses_commission_id", updatable = false)
    private UUID reversesCommissionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommissionRecordStatus status;

    @Column(nullable = false, updatable = false)
    private String period;

    /** The portfolio's day of the payment (or of its return): the period and the voucher's date. */
    @Column(name = "value_date", updatable = false)
    private LocalDate valueDate;

    @Column(name = "accrual_date", nullable = false, updatable = false)
    private Instant accrualDate;

    @Column(name = "liquidation_batch_id")
    private UUID liquidationBatchId;

    /** How it is settled: {@code NETTED} against the distributor's statement, {@code PAYOUT} in a monthly lot (null: payout). */
    @Column(name = "settlement", length = 12, updatable = false)
    private String settlement;

    /** A netted commission: the statement it was netted against, its due date and how late the payment landed. */
    @Column(name = "cycle_number", updatable = false)
    private Integer cycleNumber;

    @Column(name = "due_date", updatable = false)
    private LocalDate dueDate;

    @Column(name = "days_late", updatable = false)
    private Integer daysLate;

    public static final String NETTED = "NETTED";

    protected CommissionRecord() {}

    /**
     * The distributor's commission on a statement, netted from what she delivered against it: her flat share of what
     * her clients paid that statement ({@code basis}), at the tier of the day the payment landed ({@code rate}); the
     * amount is what the portfolio settled, to the cent. Compensated the moment it is born: it never waits for a lot
     * and carries no IVA of its own (it is a discount on what she delivers, not a payment made to her).
     */
    public static CommissionRecord netted(CommissionType commissionType, CommissionSource source, BigDecimal basis,
                                          BigDecimal rate, BigDecimal amount, long daysLate, LocalDate dueDate,
                                          int cycleNumber) {
        CommissionRecord r = from(source);
        r.commissionType = commissionType;
        r.basis          = basis != null ? basis : BigDecimal.ZERO;
        r.rate           = rate != null ? rate : BigDecimal.ZERO;
        r.amount         = amount.setScale(2, RoundingMode.HALF_UP);
        r.ivaAmount      = BigDecimal.ZERO;
        r.status         = CommissionRecordStatus.COMPENSATED;
        r.settlement     = NETTED;
        r.cycleNumber    = cycleNumber;
        r.dueDate        = dueDate;
        r.daysLate       = (int) daysLate;
        return r;
    }

    /** The undo of a netted commission whose payment came back: the same statement, the amount negated. */
    public static CommissionRecord nettedReversalOf(CommissionRecord original, String sourceEventId, LocalDate valueDate) {
        CommissionRecord r = from(new CommissionSource(original.creditAccountId, original.beneficiaryPartyId,
                original.productType, sourceEventId, original.paymentRef, valueDate));
        r.commissionType       = original.commissionType;
        r.rate                 = original.rate;
        r.reversesCommissionId = original.commissionId;
        r.basis                = original.basis.negate();
        r.amount               = original.amount.negate();
        r.ivaAmount            = BigDecimal.ZERO;
        r.status               = CommissionRecordStatus.COMPENSATED;
        r.settlement           = NETTED;
        r.cycleNumber          = original.cycleNumber;
        r.dueDate              = original.dueDate;
        r.daysLate             = original.daysLate;
        return r;
    }

    public boolean isCompensated() { return status == CommissionRecordStatus.COMPENSATED; }
    public String getSettlement()  { return settlement; }
    public Integer getCycleNumber() { return cycleNumber; }
    public LocalDate getDueDate()  { return dueDate; }
    public Integer getDaysLate()   { return daysLate; }

    /** CR-01: amount = basis (interest collected) × rate (policy snapshot), and its IVA. */
    public static CommissionRecord accrue(CommissionType commissionType, CommissionSource source, BigDecimal basis,
                                          BigDecimal rate, BigDecimal ivaRate) {
        CommissionRecord r = from(source);
        r.commissionType = commissionType;
        r.basis          = basis;
        r.rate           = rate;
        // Money is in centavos: basis × rate gave 126.924 on 1,269.24 at 10 %, and the ledger carried fractions of a
        // centavo in 2120/5104 that no payment to the distributor can settle (E2E 3 Nov: 46 of 49 accruals).
        r.amount         = basis.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        r.ivaAmount      = r.amount.multiply(ivaRate).setScale(2, RoundingMode.HALF_UP);
        return r;
    }

    /**
     * What a returned payment takes back of {@code original}: the share of it the restored interest is of the interest
     * it was paid on, never more than what earlier returns of the same payment left. Null when nothing is left.
     *
     * @param restored the interest the return restores
     * @param reversed what earlier reversals of {@code original} already took back
     */
    public static CommissionRecord reversalOf(CommissionRecord original, BigDecimal restored, Reversed reversed,
                                              String sourceEventId, LocalDate valueDate) {
        BigDecimal basisLeft  = original.basis.subtract(reversed.basis());
        BigDecimal amountLeft = original.amount.subtract(reversed.amount());
        BigDecimal ivaLeft    = original.ivaAmount.subtract(reversed.iva());
        if (basisLeft.signum() <= 0 || amountLeft.signum() <= 0) return null;
        BigDecimal basis = restored.min(basisLeft);
        boolean all = basis.compareTo(basisLeft) == 0;
        BigDecimal share = basis.divide(original.basis, 12, RoundingMode.HALF_UP);
        CommissionRecord r = from(new CommissionSource(original.creditAccountId, original.beneficiaryPartyId,
                original.productType, sourceEventId, original.paymentRef, valueDate));
        r.commissionType       = original.commissionType;
        r.rate                 = original.rate;
        r.reversesCommissionId = original.commissionId;
        r.basis                = basis.negate();
        // What is left goes whole on the last return: shares rounded one by one would leave a centavo behind.
        r.amount    = (all ? amountLeft : original.amount.multiply(share).setScale(2, RoundingMode.HALF_UP).min(amountLeft)).negate();
        r.ivaAmount = (all ? ivaLeft : original.ivaAmount.multiply(share).setScale(2, RoundingMode.HALF_UP).min(ivaLeft)).negate();
        return r;
    }

    /** What earlier reversals of a commission took back, as positive amounts. */
    public record Reversed(BigDecimal basis, BigDecimal amount, BigDecimal iva) {
        public static final Reversed NONE = new Reversed(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        public Reversed plus(CommissionRecord reversal) {
            return new Reversed(basis.subtract(reversal.basis), amount.subtract(reversal.amount),
                    iva.subtract(reversal.ivaAmount));
        }
    }

    private static CommissionRecord from(CommissionSource source) {
        CommissionRecord r = new CommissionRecord();
        LocalDate day = source.valueDate() != null ? source.valueDate() : BusinessClock.current().today();
        r.commissionId       = UUID.randomUUID();
        r.creditAccountId    = source.creditAccountId();
        r.beneficiaryPartyId = source.beneficiaryPartyId();
        r.productType        = source.productType();
        r.sourceEventId      = source.sourceEventId();
        r.paymentRef         = source.paymentRef();
        r.status             = CommissionRecordStatus.ACCRUED;
        // The portfolio's day of the payment, not the day this service read it: a payment of 31 March read on 1 April
        // is March's commission.
        r.period             = YearMonth.from(day).toString().replace("-", "");
        r.valueDate          = day;
        r.accrualDate        = BusinessClock.current().now();
        return r;
    }

    public void markLiquidated(UUID batchId) {
        if (status != CommissionRecordStatus.ACCRUED) {
            throw new InvalidCommissionRecordStateException(
                    "CommissionRecord " + commissionId + " cannot be liquidated — status " + status);
        }
        this.status             = CommissionRecordStatus.LIQUIDATED;
        this.liquidationBatchId = batchId;
    }

    public boolean isAccrued()  { return status == CommissionRecordStatus.ACCRUED; }
    public boolean isReversal() { return reversesCommissionId != null; }

    /** What is owed to the beneficiary for it: the commission and its IVA. */
    public BigDecimal gross() { return amount.add(ivaAmount); }

    public UUID getCommissionId()             { return commissionId; }
    public CommissionType getCommissionType() { return commissionType; }
    public UUID getCreditAccountId()          { return creditAccountId; }
    public UUID getBeneficiaryPartyId()       { return beneficiaryPartyId; }
    public String getProductType()            { return productType; }
    public String getSourceEventId()          { return sourceEventId; }
    public String getPaymentRef()             { return paymentRef; }
    public BigDecimal getBasis()              { return basis; }
    public BigDecimal getRate()               { return rate; }
    public BigDecimal getAmount()             { return amount; }
    public BigDecimal getIvaAmount()          { return ivaAmount; }
    public UUID getReversesCommissionId()     { return reversesCommissionId; }
    public CommissionRecordStatus getStatus() { return status; }
    public String getPeriod()                 { return period; }
    public LocalDate getValueDate()           { return valueDate; }
    public Instant getAccrualDate()           { return accrualDate; }
    public UUID getLiquidationBatchId()       { return liquidationBatchId; }
}
