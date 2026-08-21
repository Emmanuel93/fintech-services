package com.fintech.collections.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Local read-model of credit-portfolio's account, synced from credit-account-activated +
 * balance-updated — same pattern as charges-service/payments's own AccountBalanceSnapshot.
 * Collections needs this for things neither DelinquencyStatusUpdated nor a synchronous ACL
 * carries: CollectionCase.productType/totalDebt, and the principal/interest/penalty split
 * recorded on WriteOffRecord.
 */
@Entity
@Table(name = "account_balance_snapshots", schema = "collections")
public class AccountBalanceSnapshot {

    @Id
    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false)
    private UUID obligorPartyId;

    @Column(name = "product_type", nullable = false)
    private String productType;

    @Column(name = "principal_balance", nullable = false)
    private BigDecimal principalBalance;

    @Column(name = "accrued_interest_balance", nullable = false)
    private BigDecimal accruedInterestBalance;

    @Column(name = "penalty_balance", nullable = false)
    private BigDecimal penaltyBalance;

    @Column(name = "total_debt", nullable = false)
    private BigDecimal totalDebt;

    @Column(name = "balance_version", nullable = false)
    private long balanceVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountBalanceSnapshot() {}

    public static AccountBalanceSnapshot init(UUID creditAccountId, UUID obligorPartyId, String productType) {
        AccountBalanceSnapshot s = new AccountBalanceSnapshot();
        s.creditAccountId        = creditAccountId;
        s.obligorPartyId         = obligorPartyId;
        s.productType             = productType != null ? productType : "UNKNOWN";
        s.principalBalance       = BigDecimal.ZERO;
        s.accruedInterestBalance = BigDecimal.ZERO;
        s.penaltyBalance         = BigDecimal.ZERO;
        s.totalDebt              = BigDecimal.ZERO;
        s.balanceVersion         = -1L;
        s.updatedAt              = Instant.now();
        return s;
    }

    public void upsert(BigDecimal principalBalance, BigDecimal accruedInterestBalance,
                        BigDecimal penaltyBalance, BigDecimal totalDebt, long balanceVersion) {
        if (balanceVersion <= this.balanceVersion) return; // stale/duplicate delivery — ignore
        this.principalBalance       = principalBalance;
        this.accruedInterestBalance = accruedInterestBalance;
        this.penaltyBalance         = penaltyBalance;
        this.totalDebt              = totalDebt;
        this.balanceVersion         = balanceVersion;
        this.updatedAt              = Instant.now();
    }

    public UUID getCreditAccountId()              { return creditAccountId; }
    public UUID getObligorPartyId()               { return obligorPartyId; }
    public String getProductType()                { return productType; }
    public BigDecimal getPrincipalBalance()       { return principalBalance; }
    public BigDecimal getAccruedInterestBalance() { return accruedInterestBalance; }
    public BigDecimal getPenaltyBalance()         { return penaltyBalance; }
    public BigDecimal getTotalDebt()              { return totalDebt; }
    public long getBalanceVersion()               { return balanceVersion; }
    public Instant getUpdatedAt()                 { return updatedAt; }
}
