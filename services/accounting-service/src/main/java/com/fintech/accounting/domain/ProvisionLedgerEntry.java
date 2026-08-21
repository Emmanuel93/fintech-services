package com.fintech.accounting.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * GL-09: cuánto de provisión ya se ha asentado por cuenta. Risk publica el monto absoluto cada
 * noche; T4 asienta solo el delta contra este registro.
 */
@Entity
@Table(name = "provision_ledger", schema = "accounting")
public class ProvisionLedgerEntry {

    @Id
    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "last_booked_provision", nullable = false)
    private BigDecimal lastBookedProvision;

    @Column(name = "last_booked_at", nullable = false)
    private Instant lastBookedAt;

    protected ProvisionLedgerEntry() {}

    public static ProvisionLedgerEntry init(UUID creditAccountId) {
        ProvisionLedgerEntry e = new ProvisionLedgerEntry();
        e.creditAccountId     = creditAccountId;
        e.lastBookedProvision = BigDecimal.ZERO;
        e.lastBookedAt        = Instant.now();
        return e;
    }

    /** Fija el nuevo monto provisionado y devuelve el delta a asentar (positivo = deterioro). */
    public BigDecimal book(BigDecimal newProvision) {
        BigDecimal delta = newProvision.subtract(lastBookedProvision);
        this.lastBookedProvision = newProvision;
        this.lastBookedAt        = Instant.now();
        return delta;
    }

    /** Consumo de reserva (quebranto/quita) — reduce lo provisionado, nunca por debajo de cero. */
    public BigDecimal consume(BigDecimal amount) {
        BigDecimal consumed = amount.min(lastBookedProvision);
        this.lastBookedProvision = lastBookedProvision.subtract(consumed);
        this.lastBookedAt = Instant.now();
        return consumed;
    }

    public UUID getCreditAccountId()       { return creditAccountId; }
    public BigDecimal getLastBookedProvision() { return lastBookedProvision; }
    public Instant getLastBookedAt()       { return lastBookedAt; }
}
