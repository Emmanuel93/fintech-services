package com.fintech.payments.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

/**
 * Read model: local projection of credit-portfolio balance state.
 * Updated on every {@code credit-portfolio.balance-updated} event.
 * Used for pre-validation before publishing {@code payments.payment-applied}.
 *
 * PY-04: This is eventually consistent — the post-check in credit-portfolio is authoritative.
 */
@Entity
@Table(schema = "payments", name = "account_balance_snapshots")
public class AccountBalanceSnapshot {

    @Id
    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false)
    private UUID obligorPartyId;

    @Column(name = "principal_balance", nullable = false)
    private BigDecimal principalBalance;

    @Column(name = "accrued_interest_balance", nullable = false)
    private BigDecimal accruedInterestBalance;

    @Column(name = "penalty_balance", nullable = false)
    private BigDecimal penaltyBalance;

    /** creditLimit - principalBalance (revolving only; null for installment). */
    @Column(name = "available_credit")
    private BigDecimal availableCredit;

    /** principalBalance + accruedInterestBalance + penaltyBalance. */
    @Column(name = "total_debt", nullable = false)
    private BigDecimal totalDebt;

    @Column(name = "credit_limit")
    private BigDecimal creditLimit;

    @Column(name = "balance_version", nullable = false)
    private long balanceVersion;

    @Column(name = "account_status", nullable = false)
    private String accountStatus;

    @Column(name = "snapshot_at", nullable = false)
    private Instant snapshotAt;

    /** When the loan was activated: its seniority when a credit is applied by rule. */
    @Column(name = "activated_at")
    private Instant activatedAt;

    /** The numeric reference a SPEI transfer uses to name this loan. */
    @Column(name = "payment_reference")
    private Long paymentReference;

    @Column(name = "product_type")
    private String productType;

    protected AccountBalanceSnapshot() {}

    public static AccountBalanceSnapshot init(UUID creditAccountId, UUID obligorPartyId,
                                               BigDecimal creditLimit) {
        AccountBalanceSnapshot s = new AccountBalanceSnapshot();
        s.creditAccountId        = creditAccountId;
        s.obligorPartyId         = obligorPartyId;
        s.principalBalance       = BigDecimal.ZERO;
        s.accruedInterestBalance = BigDecimal.ZERO;
        s.penaltyBalance         = BigDecimal.ZERO;
        s.totalDebt              = BigDecimal.ZERO;
        s.availableCredit        = creditLimit;
        s.creditLimit            = creditLimit;
        s.balanceVersion         = 0;
        s.accountStatus          = "PENDING_ACTIVATION";
        s.snapshotAt             = BusinessClock.current().now();
        return s;
    }

    public void update(BigDecimal principalBalance, BigDecimal accruedInterestBalance,
                       BigDecimal penaltyBalance, BigDecimal availableCredit,
                       BigDecimal totalDebt, long balanceVersion, String accountStatus) {
        this.principalBalance       = principalBalance;
        this.accruedInterestBalance = accruedInterestBalance;
        this.penaltyBalance         = penaltyBalance;
        this.availableCredit        = availableCredit;
        this.totalDebt              = totalDebt;
        this.balanceVersion         = balanceVersion;
        this.accountStatus          = accountStatus;
        this.snapshotAt             = BusinessClock.current().now();
    }

    /** What the activation tells about the loan: its age, its payment reference and its product. */
    public void describe(Instant activatedAt, Long paymentReference, String productType) {
        if (activatedAt != null) this.activatedAt = activatedAt;
        if (paymentReference != null) this.paymentReference = paymentReference;
        if (productType != null) this.productType = productType;
    }

    public Instant getActivatedAt()   { return activatedAt; }
    public Long getPaymentReference() { return paymentReference; }
    public String getProductType()    { return productType; }

    /** True only for accounts that can receive payments (not terminal). */
    public boolean isAccountActive() {
        return "ACTIVE".equals(accountStatus) || "SUSPENDED".equals(accountStatus);
    }

    /**
     * A written-off loan still has an owner who may pay: that money is a recovery (IFRS 9 §5.4.4 — the asset left
     * the balance sheet, the right to collect did not). It is accepted and booked as such downstream.
     */
    public boolean isWrittenOff() {
        return "WRITTEN_OFF".equals(accountStatus);
    }

    /** Validates the payment amount is positive. Overpayments are allowed — the service handles excess. */
    public boolean canAcceptPayment(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    public UUID getCreditAccountId()          { return creditAccountId; }
    public UUID getObligorPartyId()           { return obligorPartyId; }
    public BigDecimal getPrincipalBalance()   { return principalBalance; }
    public BigDecimal getAccruedInterestBalance() { return accruedInterestBalance; }
    public BigDecimal getPenaltyBalance()     { return penaltyBalance; }
    public BigDecimal getAvailableCredit()    { return availableCredit; }
    public BigDecimal getTotalDebt()          { return totalDebt; }
    public BigDecimal getCreditLimit()        { return creditLimit; }
    public long getBalanceVersion()           { return balanceVersion; }
    public String getAccountStatus()          { return accountStatus; }
    public Instant getSnapshotAt()            { return snapshotAt; }
}
