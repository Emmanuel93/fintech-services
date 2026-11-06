package com.fintech.accounting.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

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

    /** The stage risk last measured for the account (STAGE_1, STAGE_2, STAGE_3). */
    @Column(name = "ifrs9_stage", length = 10)
    private String ifrs9Stage;

    /** The exposure risk last measured: the gross carrying amount the reserve covers. */
    @Column(name = "ead")
    private BigDecimal ead;

    /** When risk measured it (its calculatedAt): the month-end close checks the allowance is measured at its date. */
    @Column(name = "measured_at")
    private Instant measuredAt;

    protected ProvisionLedgerEntry() {}

    public static ProvisionLedgerEntry init(UUID creditAccountId) {
        ProvisionLedgerEntry e = new ProvisionLedgerEntry();
        e.creditAccountId     = creditAccountId;
        e.lastBookedProvision = BigDecimal.ZERO;
        e.lastBookedAt        = BusinessClock.current().now();
        return e;
    }

    /** Fija el nuevo monto provisionado y devuelve el delta a asentar (positivo = deterioro). */
    public BigDecimal book(BigDecimal newProvision) {
        BigDecimal delta = newProvision.subtract(lastBookedProvision);
        this.lastBookedProvision = newProvision;
        this.lastBookedAt        = BusinessClock.current().now();
        return delta;
    }

    /** Consumo de reserva (quebranto/quita) — reduce lo provisionado, nunca por debajo de cero. */
    public BigDecimal consume(BigDecimal amount) {
        BigDecimal consumed = amount.min(lastBookedProvision);
        this.lastBookedProvision = lastBookedProvision.subtract(consumed);
        this.lastBookedAt = BusinessClock.current().now();
        return consumed;
    }

    /** Remembers what risk measured with the reserve: the stage and the exposure it covers. */
    public void observe(String stage, BigDecimal exposure) {
        if (stage != null) this.ifrs9Stage = stage;
        if (exposure != null) this.ead = exposure;
    }

    /** Remembers when risk measured it; an older measurement arriving late does not move it back. */
    public void measuredAt(Instant at) {
        if (at != null && (measuredAt == null || at.isAfter(measuredAt))) this.measuredAt = at;
    }

    /**
     * The part of a Stage 3 interest charge that is not revenue: the charge times the loss allowance's coverage of
     * the gross carrying amount (IFRS 9 §5.4.1(b): revenue is the rate on the amortised cost). Zero outside Stage 3
     * or without a measured exposure. Rounded to cents, never more than the charge.
     */
    public BigDecimal creditImpairedShareOf(BigDecimal interest) {
        if (!"STAGE_3".equals(ifrs9Stage) || ead == null || ead.signum() <= 0 || lastBookedProvision.signum() <= 0
                || interest == null || interest.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal coverage = lastBookedProvision.divide(ead, 10, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        return interest.multiply(coverage).setScale(2, RoundingMode.HALF_UP).min(interest);
    }

    /**
     * The unrecognized interest goes to the loss allowance when it is charged: the reserve booked grows by it, so the
     * night's assessment books only what is left to reach risk's figure, and 1290 keeps matching this record.
     */
    public void addToAllowance(BigDecimal amount) {
        this.lastBookedProvision = lastBookedProvision.add(amount);
        this.lastBookedAt = BusinessClock.current().now();
    }

    public String getIfrs9Stage()          { return ifrs9Stage; }
    public BigDecimal getEad()             { return ead; }
    public UUID getCreditAccountId()       { return creditAccountId; }
    public BigDecimal getLastBookedProvision() { return lastBookedProvision; }
    public Instant getLastBookedAt()       { return lastBookedAt; }
    public Instant getMeasuredAt()         { return measuredAt; }
}
