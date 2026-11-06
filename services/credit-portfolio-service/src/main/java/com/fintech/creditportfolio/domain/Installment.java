package com.fintech.creditportfolio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

@Entity
@Table(name = "installments", schema = "credit_portfolio")
public class Installment {

    @Id
    @Column(name = "installment_id", nullable = false, updatable = false)
    private UUID installmentId;

    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    @Column(name = "installment_number", nullable = false)
    private int installmentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "principal_amount", nullable = false)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", nullable = false)
    private BigDecimal interestAmount;

    /**
     * El IVA que se traslada sobre el interés de esta cuota.
     *
     * <p>Va aparte del interés porque no es ingreso de la institución: es dinero del SAT que se
     * cobra y se retiene. Mezclado dentro del interés, el plan diría que se gana más de lo que se
     * gana; omitido, el total de la cuota no cuadraría contra lo que de verdad se le cobra al
     * cliente y la diferencia parecería redondeo repartido por todo el calendario.
     */
    @Column(name = "tax_amount", nullable = false)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstallmentStatus status;

    /** Cuándo se saltó este pago. Nulo = nunca se saltó. */
    @Column(name = "skipped_at")
    private java.time.Instant skippedAt;

    /** {@code GIFT} el período no devenga · {@code DEFERRAL} sí devenga y sólo se corre. */
    @Column(name = "skipped_mode", length = 12)
    private String skippedMode;

    /**
     * El vencimiento que tenía antes de moverse.
     *
     * <p>No es cosmético: es lo que permite explicarle al cliente —y a una revisión— de qué fecha a
     * qué fecha se movió su compromiso y por qué.
     */
    @Column(name = "original_due_date")
    private LocalDate originalDueDate;

    /** A cycle installment's statement cutoff: payments made up to it were already in that statement's balance. */
    @Column(name = "cutoff_date")
    private LocalDate cutoffDate;

    /** The statement cycle that billed it (statement-billed products only); null while unbilled. */
    @Column(name = "billed_cycle")
    private Integer billedCycle;

    /**
     * The business day it became fully paid; null while it is not (and for installments paid before it was kept).
     * A statement reads the plan as it stood at its cutoff: paid by then, or paid later.
     */
    @Column(name = "paid_on")
    private LocalDate paidOn;

    /**
     * The distributor's commission on this installment when she delivers its statement on time: her flat share of what
     * the client pays, capped by the plan's interest ({@code DistributorCommissionSchedule}). Null on every plan that
     * is not a distributor's placement.
     */
    @Column(name = "scheduled_commission")
    private BigDecimal scheduledCommission;

    protected Installment() {}

    public BigDecimal getScheduledCommission() { return scheduledCommission; }

    /** The commission the plan sets on this installment, written when the placement is made. */
    public void scheduleCommission(BigDecimal commission) {
        this.scheduledCommission = commission;
    }

    public static Installment of(UUID scheduleId, int number, LocalDate dueDate,
                                  BigDecimal principal, BigDecimal interest) {
        return of(scheduleId, number, dueDate, principal, interest, BigDecimal.ZERO);
    }

    public static Installment of(UUID scheduleId, int number, LocalDate dueDate,
                                  BigDecimal principal, BigDecimal interest, BigDecimal tax) {
        Installment i = new Installment();
        i.installmentId    = UUID.randomUUID();
        i.scheduleId       = scheduleId;
        i.installmentNumber = number;
        i.dueDate          = dueDate;
        i.principalAmount  = principal;
        i.interestAmount   = interest;
        i.taxAmount        = tax != null ? tax : BigDecimal.ZERO;
        i.totalAmount      = principal.add(interest).add(i.taxAmount);
        i.status           = InstallmentStatus.PENDING;
        return i;
    }

    public UUID getInstallmentId()        { return installmentId; }
    public LocalDate getCutoffDate()      { return cutoffDate; }
    public UUID getScheduleId()           { return scheduleId; }
    public int getInstallmentNumber()     { return installmentNumber; }
    public LocalDate getDueDate()         { return dueDate; }
    public BigDecimal getPrincipalAmount(){ return principalAmount; }
    public BigDecimal getInterestAmount() { return interestAmount; }
    public BigDecimal getTaxAmount()      { return taxAmount; }
    public BigDecimal getTotalAmount()    { return totalAmount; }
    public InstallmentStatus getStatus()  { return status; }
    public java.time.Instant getSkippedAt() { return skippedAt; }
    public String getSkippedMode()        { return skippedMode; }
    public LocalDate getOriginalDueDate() { return originalDueDate; }
    public LocalDate getPaidOn()          { return paidOn; }

    /**
     * Whether it was paid as of a day: paid, and on or before that day. One paid before the payment day was kept
     * counts as paid on any day.
     */
    public boolean paidBy(LocalDate day) {
        return status == InstallmentStatus.PAID && (paidOn == null || !paidOn.isAfter(day));
    }

    /**
     * Saltar este pago: el vencimiento se corre y deja de ser exigible en su fecha original.
     *
     * <p>Sin correr la fecha, saltar un pago produciría exactamente la mora que pretende evitar: el
     * envejecido seguiría encontrando la cuota vencida al día siguiente.
     *
     * @param modo {@code GIFT} si el período no devenga, {@code DEFERRAL} si sigue devengando
     */
    public void skip(String modo, LocalDate newDueDate) {
        if (status == InstallmentStatus.PAID) {
            throw new IllegalStateException(
                    "La cuota " + installmentNumber + " ya está pagada: no hay nada que saltar");
        }
        if (skippedAt != null) {
            // Sin esto, saltar dos veces la misma cuota la correría dos períodos y el tope por
            // ciclo dejaría de significar nada.
            throw new IllegalStateException(
                    "La cuota " + installmentNumber + " ya se saltó el " + skippedAt);
        }
        this.originalDueDate = dueDate;
        this.dueDate         = newDueDate;
        this.skippedAt       = BusinessClock.current().now();
        this.skippedMode     = modo;
        // Vuelve a PENDING: una cuota que ya se había vencido y se salta deja de estar vencida.
        this.status          = InstallmentStatus.PENDING;
    }

    public boolean wasSkipped() { return skippedAt != null; }

    /**
     * Corre el vencimiento sin marcar la cuota como saltada.
     *
     * <p>Es lo que le pasa a las cuotas <b>detrás</b> de una saltada: se mueven, pero no consumen
     * el tope de saltos del cliente ni cuentan como beneficio otorgado.
     */
    public void shiftDueDate(LocalDate newDueDate) {
        if (originalDueDate == null) {
            this.originalDueDate = dueDate;
        }
        this.dueDate = newDueDate;
    }

    /**
     * Billed by a statement of a statement-billed product: from now on it is due on that statement's
     * payment due date, never earlier, and the next statement does not bill it again. Billing twice
     * changes nothing.
     */
    /** A statement's own cycle installment: billed by it, and issued on its cutoff. */
    public void billAsStatement(int cycleNumber, LocalDate cutoff, LocalDate paymentDueDate) {
        this.cutoffDate = cutoff;
        billIn(cycleNumber, paymentDueDate);
    }

    public void billIn(int cycleNumber, LocalDate paymentDueDate) {
        if (billedCycle != null) {
            return;
        }
        this.billedCycle = cycleNumber;
        if (paymentDueDate != null && paymentDueDate.isAfter(dueDate)) {
            if (originalDueDate == null) {
                this.originalDueDate = dueDate;
            }
            this.dueDate = paymentDueDate;
        }
    }

    public Integer getBilledCycle() { return billedCycle; }

    /**
     * Sets the status a statement-billed product derives from its payments (covered statements). Returns whether it
     * changed. A skipped installment keeps its own state.
     */
    public boolean settleAs(InstallmentStatus covered) {
        if (skippedAt != null || status == covered) {
            return false;
        }
        moveTo(covered);
        return true;
    }

    /** Changes the status and keeps the day it became paid: today when it becomes PAID, none when it stops being. */
    private void moveTo(InstallmentStatus next) {
        if (next == InstallmentStatus.PAID && status != InstallmentStatus.PAID) {
            this.paidOn = BusinessClock.current().today();
        } else if (next != InstallmentStatus.PAID) {
            this.paidOn = null;
        }
        this.status = next;
    }

    /**
     * The status an installment loan derives from the payments received (net of returns), covering the oldest
     * first. Unlike {@link #settleAs}, a skipped installment is settled too: skipping moved its date, it did not
     * pay it. Returns whether it changed. A replaced installment is never touched.
     */
    public boolean reconcileTo(InstallmentStatus covered) {
        if (status.isVoid() || status == covered) {
            return false;
        }
        moveTo(covered);
        return true;
    }

    /** A restructure superseded it: it stays for the record and owes nothing. */
    public void replace() {
        this.status = InstallmentStatus.REPLACED;
    }

    /**
     * Its credit was cancelled (the money never reached the borrower): it stays for the record and owes nothing. A paid
     * one keeps its status: what was paid is the borrower's, and goes back as a balance in favor.
     *
     * @return whether it changed
     */
    public boolean cancel() {
        if (status == InstallmentStatus.PAID || status.isVoid()) {
            return false;
        }
        this.status = InstallmentStatus.CANCELLED;
        return true;
    }

    /** El período saltado como regalo no devenga: es una recompensa, no un aplazamiento. */
    public boolean doesNotAccrue() { return "GIFT".equals(skippedMode); }

    /**
     * Aplica [amount] a esta mensualidad y devuelve lo que sobró.
     *
     * <p>Cubrirla por completo la marca PAID; cubrirla a medias, PARTIAL. Es lo
     * que mantiene el calendario a la par del saldo: sin esto un pago baja el
     * adeudo pero el plan sigue diciendo "pago 0 de 12", que es justo lo que el
     * cliente mira para saber si su dinero entró.
     *
     * <p>No se registra el abono parcial acumulado —el estado PARTIAL basta
     * para no darla por cubierta—; llevar el saldo por mensualidad exige una
     * columna que hoy no existe.
     */
    public BigDecimal applyPayment(BigDecimal amount) {
        if (status == InstallmentStatus.PAID || amount == null
                || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return amount == null ? BigDecimal.ZERO : amount;
        }
        if (amount.compareTo(totalAmount) >= 0) {
            moveTo(InstallmentStatus.PAID);
            return amount.subtract(totalAmount);
        }
        moveTo(InstallmentStatus.PARTIAL);
        return BigDecimal.ZERO;
    }
}
