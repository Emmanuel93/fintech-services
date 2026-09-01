package com.fintech.wallet.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money leaving the user's own walletBalance — spend/transfer-out, not a debt repayment
 * (that's PaymentInstruction). Debited from WalletView.walletBalance synchronously on
 * creation. El retiro queda PENDING y lo paga `disbursement` al consumir
 * `wallet.withdrawal-completed` — el camino que ya existía y que el despachador paralelo de este
 * servicio se adelantaba a confirmar (BK-12).
 */
@Entity
@Table(schema = "wallet", name = "wallet_withdrawals")
public class WalletWithdrawal {

    @Id
    @Column(name = "withdrawal_id", nullable = false, updatable = false)
    private UUID withdrawalId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Column(name = "method", nullable = false, length = 10, updatable = false)
    private String method;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "payee_account", nullable = false, updatable = false)
    private String payeeAccount;

    @Column(name = "status", nullable = false, length = 15)
    private String status;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WalletWithdrawal() {}

    public static WalletWithdrawal create(UUID creditAccountId, UUID obligorPartyId,
                                           PaymentMethod method, BigDecimal amount, String payeeAccount) {
        var w = new WalletWithdrawal();
        w.withdrawalId    = UUID.randomUUID();
        w.creditAccountId = creditAccountId;
        w.obligorPartyId  = obligorPartyId;
        w.method          = method.name();
        w.amount          = amount;
        w.payeeAccount    = payeeAccount;
        w.status          = "PENDING";
        w.createdAt       = Instant.now();
        w.updatedAt       = w.createdAt;
        return w;
    }

    public void markSent(String externalRef) {
        this.status     = "SENT";
        this.externalRef = externalRef;
        this.updatedAt  = Instant.now();
    }

    public void markFailed() {
        this.status    = "FAILED";
        this.updatedAt = Instant.now();
    }

    public UUID getWithdrawalId()    { return withdrawalId; }
    public UUID getCreditAccountId() { return creditAccountId; }
    public UUID getObligorPartyId()  { return obligorPartyId; }
    public String getMethod()        { return method; }
    public BigDecimal getAmount()    { return amount; }
    public String getPayeeAccount()  { return payeeAccount; }
    public String getStatus()        { return status; }
    public String getExternalRef()   { return externalRef; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getUpdatedAt()    { return updatedAt; }
}
