package com.fintech.closing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * La política de cierre de un alcance, versionada.
 *
 * <p>Es lo que hoy son <b>catorce crones constantes repartidos en ocho servicios</b>. Aquí es dato:
 * cambiar cuándo y cómo cierra un producto no requiere desplegar.
 *
 * <p>Mismo patrón que {@code ProvisionPolicy}, {@code CommissionPolicy} y {@code ScoringPolicy} ya
 * usan en el monorepo — versionada, con fecha de vigencia y un solo {@code ACTIVE} por alcance.
 */
@Entity
@Table(name = "close_cycle_policies", schema = "closing")
public class ClosePolicy {

    public enum ScopeType { GLOBAL, PRODUCT_TYPE, PRODUCT }
    public enum Status { DRAFT, ACTIVE, RETIRED }
    public enum OnUnreconciled { BLOCK_SEAL, ALERT_AND_CONTINUE }

    @Id
    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;

    @Column(name = "scope_type", nullable = false, length = 12)
    private String scopeType;

    @Column(name = "scope_value", length = 60)
    private String scopeValue;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "calendar_code", nullable = false, length = 20)
    private String calendarCode;

    @Column(name = "phases", nullable = false, length = 400)
    private String phases;

    @Column(name = "accrual_basis", nullable = false, length = 16)
    private String accrualBasis;

    @Column(name = "cutoff_rule", nullable = false, length = 24)
    private String cutoffRule;

    @Column(name = "cutoff_day")
    private Short cutoffDay;

    @Column(name = "payment_due_offset_days", nullable = false)
    private short paymentDueOffsetDays;

    @Column(name = "non_business_day_shift", nullable = false, length = 8)
    private String nonBusinessDayShift;

    @Column(name = "reconcile_tolerance", nullable = false, precision = 19, scale = 4)
    private BigDecimal reconcileTolerance;

    @Column(name = "on_unreconciled", nullable = false, length = 20)
    private String onUnreconciled;

    @Column(name = "batch_size", nullable = false)
    private int batchSize;

    @Column(name = "lease_seconds", nullable = false)
    private int leaseSeconds;

    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes;

    protected ClosePolicy() {}

    // ── Lectura tipada ───────────────────────────────────────────────────────

    public ScopeType scopeType()   { return ScopeType.valueOf(scopeType); }
    public Status status()         { return Status.valueOf(status); }
    public AccrualBasis accrualBasis() { return AccrualBasis.valueOf(accrualBasis); }
    public CutoffRule cutoffRule() { return CutoffRule.valueOf(cutoffRule); }
    public NonBusinessDayShift shift() { return NonBusinessDayShift.valueOf(nonBusinessDayShift); }
    public OnUnreconciled onUnreconciled() { return OnUnreconciled.valueOf(onUnreconciled); }

    /** Las fases que aplican a este alcance, en el orden declarado. */
    public List<ClosePhase> phaseList() {
        return Arrays.stream(phases.split(","))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .map(ClosePhase::valueOf)
                .toList();
    }

    public boolean hasPhase(ClosePhase phase) { return phaseList().contains(phase); }

    /**
     * Cuán específico es este alcance. Mayor gana: cuenta &gt; producto &gt; tipo &gt; global.
     *
     * <p>Vive en el dominio y no en la consulta SQL a propósito: la regla de precedencia es una
     * decisión de negocio y tiene que poder probarse sin base de datos.
     */
    public int specificity() {
        return switch (scopeType()) {
            case PRODUCT      -> 30;
            case PRODUCT_TYPE -> 20;
            case GLOBAL       -> 10;
        };
    }

    /** Si esta versión ya estaba vigente en la fecha de negocio dada. */
    public boolean vigenteEn(LocalDate businessDate) {
        return !effectiveDate.isAfter(businessDate);
    }

    public UUID getPolicyId()            { return policyId; }
    public String getScopeValue()        { return scopeValue; }
    public int getVersion()              { return version; }
    public LocalDate getEffectiveDate()  { return effectiveDate; }
    public String getCalendarCode()      { return calendarCode; }
    public Short getCutoffDay()          { return cutoffDay; }
    public short getPaymentDueOffsetDays() { return paymentDueOffsetDays; }
    public BigDecimal getReconcileTolerance() { return reconcileTolerance; }
    public int getBatchSize()            { return batchSize; }
    public int getLeaseSeconds()         { return leaseSeconds; }
    public int getWindowMinutes()        { return windowMinutes; }
    public String getPhases()            { return phases; }
}
