package com.fintech.charges.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model: local projection of credit-portfolio balance state for charges pre-validation.
 * Updated on every {@code credit-portfolio.balance-updated} event.
 *
 * Pre-check uses {@code accountStatus} to skip charges on terminal accounts before publishing.
 * Post-check in credit-portfolio is authoritative (publishes charge-rejected if needed).
 */
@Entity
@Table(schema = "charges", name = "account_balance_snapshots")
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

    @Column(name = "available_credit")
    private BigDecimal availableCredit;

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
        s.snapshotAt             = Instant.now();
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
        this.snapshotAt             = Instant.now();
    }

    /** Returns true if the account can receive new charges (not terminal). */
    public boolean isChargeable() {
        return !"WRITTEN_OFF".equalsIgnoreCase(accountStatus)
                && !"CLOSED".equalsIgnoreCase(accountStatus)
                && !"SETTLED".equalsIgnoreCase(accountStatus);
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
