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

    /**
     * El reloj del devengo moratorio, <b>separado del ordinario a propósito</b>.
     *
     * <p>Compartir {@code lastAccrualDate} parecía lo natural y es un error: los dos jobs corren en
     * el mismo día y el que llegue primero marcaría la fecha, dejando al otro sin devengar. Si el
     * moratorio ganara la carrera, el <b>interés ordinario de ese día no se cobraría nunca</b> —se
     * cambiaría un cargo duplicado por uno omitido, que es peor y más difícil de ver.
     */
    @Column(name = "last_moratorium_accrual_date")
    private LocalDate lastMoratoriumAccrualDate;

    @Column(name = "principal_balance", nullable = false, precision = 20, scale = 4)
    private BigDecimal principalBalance;

    /**
     * Capital de las cuotas vencidas sin cubrir. <b>La base del moratorio.</b>
     *
     * <p>Antes se usaba {@code principalBalance} —todo el saldo— y sobre un crédito de $20 000 con
     * una cuota vencida de $1 800 de capital, se cobraba mora sobre los $20 000. Once veces lo que
     * corresponde. Lo publica cartera, que es quien tiene el calendario.
     */
    @Column(name = "overdue_principal", nullable = false)
    private BigDecimal overduePrincipal;

    /**
     * Desde cuándo devenga interés ordinario. Nulo = desde el alta.
     *
     * <p>Lo fija <b>buy now pay later</b> al originar. Sin esto, un producto BNPL cobraba interés
     * desde el primer día — exactamente lo contrario de lo que promete.
     */
    @Column(name = "accrual_start_date")
    private LocalDate accrualStartDate;

    /** Vencimiento de la cuota vencida más antigua: la fecha desde la que corre la mora. */
    @Column(name = "oldest_due_date")
    private LocalDate oldestDueDate;

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
        s.moratoriumActive  = false;
        s.overduePrincipal  = BigDecimal.ZERO;
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

    /**
     * Lo que cartera acaba de medir: cuánto capital está vencido y desde cuándo.
     *
     * <p><b>Es lo único que decide si la mora se enciende o se apaga.</b> Cero capital vencido =
     * la cuenta se curó, y la mora deja de correr en la misma pasada (BK-20). Antes nadie la
     * apagaba: {@code clearMoratorium()} existía sin llamador, así que una vez encendida seguía
     * devengando aunque el cliente se pusiera al corriente.
     *
     * @param graciaEfectiva días de gracia del producto; la mora corre a partir de
     *                       {@code oldestDueDate + gracia}, no desde el vencimiento
     */
    public void actualizarMora(BigDecimal capitalVencido, LocalDate vencimientoMasAntiguo,
                               LocalDate hoy, int graciaEfectiva) {
        this.overduePrincipal = capitalVencido != null ? capitalVencido : BigDecimal.ZERO;
        this.oldestDueDate    = vencimientoMasAntiguo;
        this.updatedAt        = Instant.now();

        boolean hayVencido = overduePrincipal.compareTo(BigDecimal.ZERO) > 0
                && vencimientoMasAntiguo != null;
        if (!hayVencido) {
            clearMoratorium();
            return;
        }

        LocalDate arranque = vencimientoMasAntiguo.plusDays(graciaEfectiva);
        if (hoy.isBefore(arranque)) {
            // Dentro de la gracia no hay mora, y tampoco se deja encendida de una medición previa.
            clearMoratorium();
            return;
        }
        activateMoratorium(arranque);
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

    /** Idempotencia del devengo moratorio: uno por día, con su propio reloj. */
    public boolean needsMoratoriumAccrual(LocalDate today) {
        return AccrualScheduleStatus.ACTIVE.name().equals(status)
                && moratoriumActive
                && (lastMoratoriumAccrualDate == null || today.isAfter(lastMoratoriumAccrualDate));
    }

    public void markMoratoriumAccruedFor(LocalDate date) {
        if (lastMoratoriumAccrualDate != null && !date.isAfter(lastMoratoriumAccrualDate)) return;
        this.lastMoratoriumAccrualDate = date;
        this.updatedAt = Instant.now();
    }

    public boolean needsAccrual(LocalDate today) {
        return AccrualScheduleStatus.ACTIVE.name().equals(status)
                && yaArranco(today)
                && (lastAccrualDate == null || today.isAfter(lastAccrualDate));
    }

    /**
     * BK-28 · antes de su fecha de arranque, un crédito BNPL <b>no devenga</b>.
     *
     * <p>El día de arranque sí devenga: {@code isBefore} y no {@code isEqual}. Un tope que excluyera
     * su propio primer día regalaría una jornada de interés en cada crédito con BNPL, que es poco
     * dinero por crédito y mucho a lo largo de una cartera.
     */
    public boolean yaArranco(LocalDate dia) {
        return accrualStartDate == null || !dia.isBefore(accrualStartDate);
    }

    /**
     * Corre el arranque del devengo (BNPL).
     *
     * <p>No mueve el vencimiento de las cuotas: eso es de cartera, que es dueña del plan. Los dos
     * se corren, y correr sólo uno es el error que hay que evitar — con el devengo corrido y el
     * plan quieto, el cliente no paga interés pero su primera cuota vence igual.
     */
    public void arrancarDevengoEl(LocalDate fecha) {
        this.accrualStartDate = fecha;
        this.updatedAt        = Instant.now();
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
        // Los dos relojes se retroceden juntos: sembrar historia con uno adelantado dejaría
        // meses sin moratorios o sin interés, según cuál quedara atrás.
        this.lastMoratoriumAccrualDate = date;
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
    public BigDecimal getOverduePrincipal() { return overduePrincipal; }
    public LocalDate getOldestDueDate()     { return oldestDueDate; }
    public LocalDate getAccrualStartDate()  { return accrualStartDate; }
    public LocalDate getLastMoratoriumAccrualDate() { return lastMoratoriumAccrualDate; }
    public LocalDate getLastAccrualDate() { return lastAccrualDate; }
    public BigDecimal getPrincipalBalance() { return principalBalance; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public Instant getCreatedAt()         { return createdAt; }
    public Instant getUpdatedAt()         { return updatedAt; }
}
