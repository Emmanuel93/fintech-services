package com.fintech.commission.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Local shadow of {@code accruedInterestBalance} per credit account. {@code PaymentApplied} doesn't
 * carry the interest/principal split, so Commission derives "interest collected" from the delta
 * against the previous {@code balance-updated} — same pattern as Accounting's own shadow (T4).
 */
@Entity
@Table(name = "account_balance_shadows", schema = "commission")
public class AccountBalanceShadow {

    @Id
    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "interest_balance", nullable = false)
    private BigDecimal interestBalance;

    @Column(name = "balance_version", nullable = false)
    private long balanceVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountBalanceShadow() {}

    public static AccountBalanceShadow init(UUID creditAccountId) {
        AccountBalanceShadow s = new AccountBalanceShadow();
        s.creditAccountId  = creditAccountId;
        s.interestBalance  = BigDecimal.ZERO;
        s.balanceVersion   = -1L;
        s.updatedAt        = Instant.now();
        return s;
    }

    /**
     * Applies the new interest balance and returns the delta (old - new): positive when interest
     * dropped (collected via a payment), negative when it rose (a new charge, or — ideally — a
     * returned payment un-collecting interest). Returns null for a stale/duplicate delivery.
     */
    public BigDecimal applyAndComputeInterestCollected(BigDecimal newInterestBalance, long version) {
        if (version <= this.balanceVersion) return null;
        BigDecimal collected = this.interestBalance.subtract(newInterestBalance);
        this.interestBalance = newInterestBalance;
        this.balanceVersion  = version;
        this.updatedAt       = Instant.now();
        return collected;
    }

    public UUID getCreditAccountId()     { return creditAccountId; }
    public BigDecimal getInterestBalance() { return interestBalance; }
    public long getBalanceVersion()      { return balanceVersion; }
}
