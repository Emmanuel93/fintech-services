package com.fintech.creditportfolio.domain.relief;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * El expediente de una cuenta apoyada (BK-36).
 *
 * <p>Guarda qué programa la alcanzó, con cuántos días de atraso entró, cuántas cuotas se corrieron y
 * de qué fecha a qué fecha. Es lo que permite sustentar el apoyo ante el cliente y ante una revisión
 * — sin esto, «se te movió el pago» no tiene respaldo.
 */
@Entity
@Table(name = "relief_enrollments", schema = "credit_portfolio")
public class ReliefEnrollment {

    @Id
    @Column(name = "enrollment_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "relief_program_id", nullable = false, updatable = false)
    private UUID reliefProgramId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "days_delinquent_at_enrollment", nullable = false)
    private int daysDelinquentAtEnrollment;

    @Column(name = "installments_moved", nullable = false)
    private int installmentsMoved;

    @Column(name = "first_due_before")
    private LocalDate firstDueBefore;

    @Column(name = "first_due_after")
    private LocalDate firstDueAfter;

    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected ReliefEnrollment() {}

    public static ReliefEnrollment de(UUID programa, UUID cuenta, int dpd, int cuotasMovidas,
                                      LocalDate antes, LocalDate despues) {
        ReliefEnrollment e = new ReliefEnrollment();
        e.id                         = UUID.randomUUID();
        e.reliefProgramId            = programa;
        e.creditAccountId            = cuenta;
        e.daysDelinquentAtEnrollment = dpd;
        e.installmentsMoved          = cuotasMovidas;
        e.firstDueBefore             = antes;
        e.firstDueAfter              = despues;
        e.enrolledAt                 = Instant.now();
        return e;
    }

    /** Al vencer la vigencia el calendario retoma y el DPD vuelve a correr. */
    public void liberar() { this.releasedAt = Instant.now(); }

    public boolean estaVigente() { return releasedAt == null; }

    public UUID getId()                     { return id; }
    public UUID getReliefProgramId()        { return reliefProgramId; }
    public UUID getCreditAccountId()        { return creditAccountId; }
    public int getDaysDelinquentAtEnrollment() { return daysDelinquentAtEnrollment; }
    public int getInstallmentsMoved()       { return installmentsMoved; }
    public LocalDate getFirstDueBefore()    { return firstDueBefore; }
    public LocalDate getFirstDueAfter()     { return firstDueAfter; }
    public Instant getEnrolledAt()          { return enrolledAt; }
    public Instant getReleasedAt()          { return releasedAt; }
}
