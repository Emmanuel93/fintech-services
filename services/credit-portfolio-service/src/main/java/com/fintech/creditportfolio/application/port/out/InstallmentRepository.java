package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InstallmentRepository {
    void saveAll(List<Installment> installments);
    List<Installment> findByScheduleIdOrdered(UUID scheduleId);
    List<Installment> findByScheduleId(UUID scheduleId);

    /** Todas las cuotas de varios calendarios — el plan completo de una revolvente. */
    List<Installment> findByScheduleIds(java.util.Collection<UUID> scheduleIds);
    List<Installment> findPendingOverdueByScheduleId(UUID scheduleId, LocalDate cutoff);

    /**
     * Cuotas vencidas de varios calendarios a la vez.
     *
     * <p>Para las revolventes: su deuda no vive en un calendario sino en uno por disposición, y la
     * mora se mide contra el conjunto. Se pide en una consulta y no una por disposición, porque el
     * envejecido recorre toda la cartera cada noche y una línea con veinte colocaciones haría
     * veinte viajes a la base por cuenta.
     */
    List<Installment> findPendingOverdueByScheduleIds(java.util.Collection<UUID> scheduleIds,
                                                      LocalDate cutoff);
    List<Installment> findDueToday(LocalDate today);
    /**
     * Pendientes que vencen hoy <b>o que ya vencieron</b>.
     *
     * <p>El job de vencimiento corre una vez al día: si falla una noche, o si
     * la fecha se movió, una mensualidad que sólo se buscara por "vence hoy"
     * se quedaría PENDING para siempre y el cliente nunca sabría que está en
     * mora. Se busca por "vence hoy o antes" para que ninguna se pierda.
     */
    List<Installment> findDueOnOrBefore(LocalDate date);
    /** For UpcomingInstallmentJob (early collections) — PENDING installments due on a future date. */
    List<Installment> findDueOn(LocalDate date);
    /**
     * Avance del plan de varias cuentas de una sola vez, indexado por cuenta.
     * Es lo que evita el N+1 del listado del backoffice.
     */
    java.util.Map<UUID, com.fintech.creditportfolio.domain.PaymentPlanSummary>
            planSummaries(java.util.Collection<UUID> scheduleIds);

    /** Dev-only (test-support): reasigna due_date para simular el paso del tiempo. */
    void shiftDueDate(UUID scheduleId, int installmentNumber, LocalDate newDueDate);
}
