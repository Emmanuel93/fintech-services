package com.fintech.charges.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "charges", name = "accrual_schedules")
public class AccrualSchedule {

    @Id
    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "credit_account_id", nullable = false, unique = true, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Column(name = "product_type", nullable = false, updatable = false)
    private String productType;

    @Column(name = "product_behavior", nullable = false, updatable = false)
    private String productBehavior;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "nominal_rate", nullable = false, precision = 12, scale = 8)
    private BigDecimal nominalRate;

    @Column(name = "moratorium_rate", nullable = false, precision = 12, scale = 8)
    private BigDecimal moratoriumRate;

    @Column(name = "moratorium_active", nullable = false)
    private boolean moratoriumActive;

    @Column(name = "moratorium_start_date")
    private LocalDate moratoriumStartDate;

    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays;

    @Column(name = "last_accrual_date")
    private LocalDate lastAccrualDate;

    @Column(name = "principal_balance", nullable = false, precision = 20, scale = 4)
    private BigDecimal principalBalance;

    @Column(name = "approved_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal approvedAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccrualSchedule() {}

    public static AccrualSchedule create(UUID creditAccountId, UUID obligorPartyId,
                                          String productType, String productBehavior,
                                          BigDecimal nominalRate, BigDecimal moratoriumRate,
                                          BigDecimal principalBalance, BigDecimal approvedAmount,
                                          int gracePeriodDays) {
        var s = new AccrualSchedule();
        s.scheduleId       = UUID.randomUUID();
        s.creditAccountId  = creditAccountId;
        s.obligorPartyId   = obligorPartyId;
        s.productType      = productType;
        s.productBehavior  = productBehavior;
        s.status           = AccrualScheduleStatus.ACTIVE.name();
        s.nominalRate      = nominalRate;
        s.moratoriumRate   = moratoriumRate;
        s.moratoriumActive = false;
        s.gracePeriodDays  = gracePeriodDays;
        s.lastAccrualDate  = null;
        s.principalBalance = principalBalance;
        s.approvedAmount   = approvedAmount;
        s.createdAt        = Instant.now();
        s.updatedAt        = s.createdAt;
        return s;
    }

    public void activateMoratorium(LocalDate startDate) {
        if (AccrualScheduleStatus.CLOSED.name().equals(status)) {
            throw new InvalidChargeStateException("Cannot activate moratorium on CLOSED schedule " + scheduleId);
        }
        this.moratoriumActive    = true;
        this.moratoriumStartDate = startDate;
        this.updatedAt           = Instant.now();
    }

    public void clearMoratorium() {
        this.moratoriumActive = false;
        this.updatedAt        = Instant.now();
    }

    public void updateBalance(BigDecimal newPrincipalBalance) {
        this.principalBalance = newPrincipalBalance;
        this.updatedAt        = Instant.now();
    }

    public void close() {
        this.status           = AccrualScheduleStatus.CLOSED.name();
        this.moratoriumActive = false;
        this.updatedAt        = Instant.now();
    }

    public void markAccruedFor(LocalDate date) {
        if (lastAccrualDate != null && !date.isAfter(lastAccrualDate)) return;
        this.lastAccrualDate = date;
        this.updatedAt       = Instant.now();
    }

    public boolean needsAccrual(LocalDate today) {
        return AccrualScheduleStatus.ACTIVE.name().equals(status)
                && (lastAccrualDate == null || today.isAfter(lastAccrualDate));
    }

    /**
     * Retrocede el reloj del devengo. <b>Sólo para siembra</b> (test-support).
     *
     * <p>{@link #markAccruedFor} nunca va hacia atrás —es la guarda que hace idempotente al job— y
     * por eso sembrar historia era imposible sin escribir en la base a mano: no había forma de
     * decirle a un calendario «tú empezaste en junio» para después correrle el reloj día a día.
     */
    public void rewindAccrualTo(LocalDate date) {
        this.lastAccrualDate = date;
        this.updatedAt       = Instant.now();
    }

    public boolean isActive() { return AccrualScheduleStatus.ACTIVE.name().equals(status); }

    public UUID getScheduleId()           { return scheduleId; }
    public UUID getCreditAccountId()      { return creditAccountId; }
    public UUID getObligorPartyId()       { return obligorPartyId; }
    public String getProductType()        { return productType; }
    public String getProductBehavior()    { return productBehavior; }
    public String getStatus()             { return status; }
    public BigDecimal getNominalRate()    { return nominalRate; }
    public BigDecimal getMoratoriumRate() { return moratoriumRate; }
    public boolean isMoratoriumActive()   { return moratoriumActive; }
    public LocalDate getMoratoriumStartDate() { return moratoriumStartDate; }
    public int getGracePeriodDays()       { return gracePeriodDays; }
    public LocalDate getLastAccrualDate() { return lastAccrualDate; }
    public BigDecimal getPrincipalBalance() { return principalBalance; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public Instant getCreatedAt()         { return createdAt; }
    public Instant getUpdatedAt()         { return updatedAt; }
}
