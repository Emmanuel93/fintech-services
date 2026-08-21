package com.fintech.wallet.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Proyección del estado de CreditProduct visible al usuario.
 * Invariante WV-01: no es fuente de verdad — solo replica eventos de credit-portfolio.
 */
@Entity
@Table(schema = "wallet", name = "wallet_views")
public class WalletView {

    @Id
    @Column(name = "wallet_id")
    private UUID walletId;

    @Column(name = "credit_account_id", nullable = false, unique = true, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Column(name = "product_type", nullable = false, updatable = false)
    private String productType;

    @Column(name = "principal_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalBalance;

    @Column(name = "accrued_interest_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal accruedInterestBalance;

    @Column(name = "penalty_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal penaltyBalance;

    @Column(name = "total_debt", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalDebt;

    // WV-03: only present for revolving products
    @Column(name = "available_credit", precision = 19, scale = 4)
    private BigDecimal availableCredit;

    /**
     * Spendable balance already disposed from the credit line and not yet withdrawn.
     * Still a projection (WP-01): accumulates two event streams — credited by
     * credit-portfolio.disposition-completed (SELF_USE only), debited by wallet's own
     * withdrawals. credit-portfolio never sees the debit side (it only knows the money
     * left the line, not whether it was later spent), so this can't be a single-field
     * mirror like the rest of this entity — it's a running total instead.
     */
    @Column(name = "wallet_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal walletBalance;

    // From AccountStatementGenerated — not calculated here (WP-03)
    @Column(name = "minimum_payment", precision = 19, scale = 4)
    private BigDecimal minimumPayment;

    @Column(name = "payment_due_date")
    private LocalDate paymentDueDate;

    // Only for installment products
    @Column(name = "next_installment_amount", precision = 19, scale = 4)
    private BigDecimal nextInstallmentAmount;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    // For DOMICILIACION — registered in Origination
    @Column(name = "registered_clabe", length = 18)
    private String registeredClabe;

    @Column(name = "balance_version", nullable = false)
    private long balanceVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletView() {}

    public static WalletView createFromActivation(
            UUID creditAccountId,
            UUID obligorPartyId,
            String productType,
            BigDecimal principalBalance,
            BigDecimal availableCredit) {
        var v = new WalletView();
        v.walletId              = UUID.randomUUID();
        v.creditAccountId       = creditAccountId;
        v.obligorPartyId        = obligorPartyId;
        v.productType           = productType;
        v.principalBalance      = principalBalance;
        v.accruedInterestBalance = BigDecimal.ZERO;
        v.penaltyBalance        = BigDecimal.ZERO;
        v.totalDebt             = principalBalance;
        v.availableCredit       = availableCredit;
        v.walletBalance         = BigDecimal.ZERO;
        v.status                = "ACTIVE";
        v.lastUpdatedAt         = Instant.now();
        v.createdAt             = v.lastUpdatedAt;
        v.balanceVersion        = 0L;
        return v;
    }

    public void applyBalanceUpdate(BigDecimal principalBalance,
                                    BigDecimal accruedInterestBalance,
                                    BigDecimal penaltyBalance,
                                    BigDecimal availableCredit,
                                    BigDecimal totalDebt,
                                    String accountStatus,
                                    long balanceVersion) {
        // WP-01: synchronous update on BalanceUpdated — never compute balances here
        this.principalBalance       = principalBalance;
        this.accruedInterestBalance = accruedInterestBalance != null ? accruedInterestBalance : BigDecimal.ZERO;
        this.penaltyBalance         = penaltyBalance != null ? penaltyBalance : BigDecimal.ZERO;
        this.totalDebt              = totalDebt != null ? totalDebt : principalBalance;
        this.availableCredit        = availableCredit;
        this.status                 = accountStatus;
        this.balanceVersion         = balanceVersion;
        this.lastUpdatedAt          = Instant.now();
    }

    public void applyInstallmentDue(BigDecimal installmentAmount, LocalDate dueDate) {
        this.nextInstallmentAmount = installmentAmount;
        this.paymentDueDate        = dueDate;
        this.lastUpdatedAt         = Instant.now();
    }

    public void applyStatementGenerated(BigDecimal minimumPayment, LocalDate paymentDueDate) {
        this.minimumPayment  = minimumPayment;
        this.paymentDueDate  = paymentDueDate;
        this.lastUpdatedAt   = Instant.now();
    }

    public void reserveCredit(BigDecimal amount) {
        if (availableCredit != null) {
            this.availableCredit = this.availableCredit.subtract(amount);
            this.lastUpdatedAt   = Instant.now();
        }
    }

    public void liberateCredit(BigDecimal amount) {
        if (availableCredit != null) {
            this.availableCredit = this.availableCredit.add(amount);
            this.lastUpdatedAt   = Instant.now();
        }
    }

    public void markSettled() {
        this.status        = "SETTLED";
        this.lastUpdatedAt = Instant.now();
    }

    public void markWrittenOff() {
        this.status        = "WRITTEN_OFF";
        this.lastUpdatedAt = Instant.now();
    }

    public void registerClabe(String clabe) {
        this.registeredClabe = clabe;
        this.lastUpdatedAt   = Instant.now();
    }

    /** DispositionCompleted (SELF_USE) → money stayed on the platform, credit the spendable balance. */
    public void credit(BigDecimal amount) {
        this.walletBalance = this.walletBalance.add(amount);
        this.lastUpdatedAt  = Instant.now();
    }

    /** Withdrawal request → debit the spendable balance. */
    public void debit(BigDecimal amount) {
        if (amount.compareTo(walletBalance) > 0) {
            throw new InsufficientWalletBalanceException(amount, walletBalance);
        }
        this.walletBalance = this.walletBalance.subtract(amount);
        this.lastUpdatedAt  = Instant.now();
    }

    public boolean isRevolvingProduct() {
        return "REVOLVING_CREDIT".equals(productType) || "DISTRIBUTOR_LINE".equals(productType);
    }

    public boolean isSuspended() {
        return "SUSPENDED".equals(status);
    }

    public UUID getWalletId()                       { return walletId; }
    public UUID getCreditAccountId()                { return creditAccountId; }
    public UUID getObligorPartyId()                 { return obligorPartyId; }
    public String getProductType()                  { return productType; }
    public BigDecimal getPrincipalBalance()         { return principalBalance; }
    public BigDecimal getAccruedInterestBalance()   { return accruedInterestBalance; }
    public BigDecimal getPenaltyBalance()           { return penaltyBalance; }
    public BigDecimal getTotalDebt()                { return totalDebt; }
    public BigDecimal getAvailableCredit()          { return availableCredit; }
    public BigDecimal getWalletBalance()            { return walletBalance; }
    public BigDecimal getMinimumPayment()           { return minimumPayment; }
    public LocalDate getPaymentDueDate()            { return paymentDueDate; }
    public BigDecimal getNextInstallmentAmount()    { return nextInstallmentAmount; }
    public String getStatus()                       { return status; }
    public Instant getLastUpdatedAt()               { return lastUpdatedAt; }
    public String getRegisteredClabe()              { return registeredClabe; }
    public long getBalanceVersion()                 { return balanceVersion; }
    public Instant getCreatedAt()                   { return createdAt; }
}
