package com.fintech.creditportfolio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

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

    protected Installment() {}

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
    public UUID getScheduleId()           { return scheduleId; }
    public int getInstallmentNumber()     { return installmentNumber; }
    public LocalDate getDueDate()         { return dueDate; }
    public BigDecimal getPrincipalAmount(){ return principalAmount; }
    public BigDecimal getInterestAmount() { return interestAmount; }
    public BigDecimal getTaxAmount()      { return taxAmount; }
    public BigDecimal getTotalAmount()    { return totalAmount; }
    public InstallmentStatus getStatus()  { return status; }

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
            status = InstallmentStatus.PAID;
            return amount.subtract(totalAmount);
        }
        status = InstallmentStatus.PARTIAL;
        return BigDecimal.ZERO;
    }
}
