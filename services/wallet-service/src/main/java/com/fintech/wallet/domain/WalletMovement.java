package com.fintech.wallet.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Renglón del ledger de movimientos del wallet (vista "Historial" de la app).
 * Se registra al ocurrir cada evento que afecta el saldo visible: disposición (crédito),
 * retiro (débito) e instrucción de pago (débito).
 */
@Entity
@Table(name = "wallet_movements", schema = "wallet")
public class WalletMovement {

    @Id
    @Column(name = "movement_id", nullable = false, updatable = false)
    private UUID movementId;

    @Column(name = "credit_account_id", nullable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false)
    private UUID obligorPartyId;

    @Column(nullable = false, length = 20)
    private String type;        // DISPOSITION | WITHDRAWAL | PAYMENT

    @Column(nullable = false, length = 6)
    private String direction;   // CREDIT | DEBIT

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 160)
    private String description;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 100)
    private String reference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletMovement() {}

    private WalletMovement(UUID creditAccountId, UUID obligorPartyId, String type, String direction,
                           BigDecimal amount, String description, String status, String reference) {
        this.movementId      = UUID.randomUUID();
        this.creditAccountId = creditAccountId;
        this.obligorPartyId  = obligorPartyId;
        this.type            = type;
        this.direction       = direction;
        this.amount          = amount;
        this.description     = description;
        this.status          = status;
        this.reference       = reference;
        this.createdAt       = Instant.now();
    }

    public static WalletMovement disposition(UUID creditAccountId, UUID obligorPartyId,
                                             BigDecimal amount, String reference) {
        return new WalletMovement(creditAccountId, obligorPartyId, "DISPOSITION", "CREDIT",
                amount, "Disposición de crédito", "COMPLETED", reference);
    }

    public static WalletMovement withdrawal(UUID creditAccountId, UUID obligorPartyId,
                                            BigDecimal amount, String payeeAccount, String reference) {
        return new WalletMovement(creditAccountId, obligorPartyId, "WITHDRAWAL", "DEBIT",
                amount, "Transferencia SPEI a " + mask(payeeAccount), "COMPLETED", reference);
    }

    public static WalletMovement payment(UUID creditAccountId, UUID obligorPartyId,
                                         BigDecimal amount, String reference) {
        return new WalletMovement(creditAccountId, obligorPartyId, "PAYMENT", "DEBIT",
                amount, "Pago a tu crédito", "PENDING", reference);
    }

    private static String mask(String account) {
        if (account == null || account.length() <= 4) return account != null ? account : "";
        return "•••• " + account.substring(account.length() - 4);
    }

    public UUID getMovementId()      { return movementId; }
    public UUID getCreditAccountId() { return creditAccountId; }
    public UUID getObligorPartyId()  { return obligorPartyId; }
    public String getType()          { return type; }
    public String getDirection()     { return direction; }
    public BigDecimal getAmount()    { return amount; }
    public String getDescription()   { return description; }
    public String getStatus()        { return status; }
    public String getReference()     { return reference; }
    public Instant getCreatedAt()    { return createdAt; }
}
