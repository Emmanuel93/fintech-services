package com.fintech.banking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lo no identificado, declarado como <b>partida en conciliación</b>.
 *
 * <p>Es la diferencia entre una conciliación y un reporte de diferencias: lo que no cuadra no
 * desaparece, sale del cierre del día con nombre, importe y motivo. Un movimiento que nadie declara
 * es un movimiento que nadie busca.
 */
@Entity
@Table(name = "suspense_entries", schema = "banking")
public class SuspenseEntry {

    @Id
    @Column(name = "suspense_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "line_id", nullable = false, updatable = false)
    private UUID lineId;

    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private UUID bankAccountId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "direction", nullable = false, length = 6)
    private String direction;

    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolved_by", length = 80)
    private String resolvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected SuspenseEntry() {}

    public static SuspenseEntry de(BankStatementLine linea, String motivo) {
        SuspenseEntry e = new SuspenseEntry();
        e.id            = UUID.randomUUID();
        e.lineId        = linea.getId();
        e.bankAccountId = linea.getBankAccountId();
        e.businessDate  = linea.getBusinessDate();
        e.amount        = linea.getAmount();
        e.direction     = linea.getDirection();
        e.reason        = motivo;
        e.status        = "OPEN";
        e.createdAt     = OffsetDateTime.now();
        return e;
    }

    /** Apareció su contraparte: sale de la puente y el flujo real registra lo suyo. */
    public void resolver(String quien) {
        if (!"OPEN".equals(status)) {
            throw new IllegalStateException("La partida " + id + " ya está " + status);
        }
        this.status     = "RESOLVED";
        this.resolvedAt = OffsetDateTime.now();
        this.resolvedBy = quien;
    }

    /**
     * Nunca se identificó y prescribe.
     *
     * <p>Tiene contrapartida contable —{@code 2109 → 4105} para un abono, {@code 5105 → 1109} para
     * un cargo— porque hasta BK-06 este estado cerraba la partida en banking y en el mayor seguía
     * viva para siempre.
     */
    public void darDeBaja(String quien) {
        if (!"OPEN".equals(status)) {
            throw new IllegalStateException("La partida " + id + " ya está " + status);
        }
        this.status     = "WRITTEN_OFF";
        this.resolvedAt = OffsetDateTime.now();
        this.resolvedBy = quien;
    }

    public boolean estaAbierta() { return "OPEN".equals(status); }

    /** El signo que aporta a la diferencia del sello: un abono suma, un cargo resta. */
    public BigDecimal aportacionAlSaldo() {
        return "CREDIT".equals(direction) ? amount : amount.negate();
    }

    public UUID getId()             { return id; }
    public UUID getLineId()         { return lineId; }
    public UUID getBankAccountId()  { return bankAccountId; }
    public LocalDate getBusinessDate(){ return businessDate; }
    public BigDecimal getAmount()   { return amount; }
    public String getDirection()    { return direction; }
    public String getReason()       { return reason; }
    public String getStatus()       { return status; }
    public String getResolvedBy()   { return resolvedBy; }
}
