package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface SpringDataInstallmentRepository extends JpaRepository<Installment, UUID> {
    List<Installment> findByScheduleIdOrderByInstallmentNumber(UUID scheduleId);

    List<Installment> findByScheduleIdIn(java.util.Collection<UUID> scheduleIds);

    @Query("SELECT i FROM Installment i WHERE i.scheduleId = :scheduleId AND i.status = :status AND i.dueDate < :cutoff")
    List<Installment> findOverdueByScheduleId(@Param("scheduleId") UUID scheduleId,
                                               @Param("status") InstallmentStatus status,
                                               @Param("cutoff") LocalDate cutoff);

    @Query("SELECT i FROM Installment i WHERE i.scheduleId IN :scheduleIds "
         + "AND i.status = :status AND i.dueDate < :cutoff")
    List<Installment> findOverdueByScheduleIds(@Param("scheduleIds") java.util.Collection<UUID> scheduleIds,
                                                @Param("status") InstallmentStatus status,
                                                @Param("cutoff") LocalDate cutoff);

    @Query("SELECT i FROM Installment i WHERE i.dueDate = :today AND i.status = :status")
    List<Installment> findByDueDateAndStatus(@Param("today") LocalDate today,
                                              @Param("status") InstallmentStatus status);

    @Query("SELECT i FROM Installment i WHERE i.dueDate <= :date AND i.status = :status "
         + "ORDER BY i.dueDate")
    List<Installment> findByDueDateNotAfterAndStatus(@Param("date") LocalDate date,
                                                      @Param("status") InstallmentStatus status);

    /**
     * Avance del plan de <b>varias</b> cuentas en una sola consulta.
     *
     * <p>El listado del backoffice muestra "pago 3 de 12" por fila. Pedir el
     * calendario cuenta por cuenta convertiría una página de 25 en 26 consultas;
     * esto agrega en la base y devuelve una fila por cuenta.
     */
    @Query(value = "SELECT schedule_id, "
                 + "       COUNT(*) FILTER (WHERE status = 'PAID') AS paid, "
                 + "       COUNT(*) AS total, "
                 + "       MIN(installment_number) FILTER (WHERE status <> 'PAID') AS next_number, "
                 + "       MIN(due_date) FILTER (WHERE status <> 'PAID') AS next_due, "
                 + "       COALESCE(SUM(principal_amount) FILTER (WHERE status = 'PAID'), 0) AS principal_paid, "
                 + "       COALESCE(SUM(total_amount) FILTER (WHERE status <> 'PAID' AND due_date < CURRENT_DATE), 0) AS overdue_amount, "
                 + "       COUNT(*) FILTER (WHERE status <> 'PAID' AND due_date < CURRENT_DATE) AS overdue_count, "
                 // El importe de la mensualidad que toca. Faltaba —el lote lo devolvía nulo
                 // mientras la ruta de una sola cuenta sí lo calculaba—, y por eso "a cobrar"
                 // salía en cero en cualquier pantalla que listara cuentas: la cifra existía en
                 // la ficha y desaparecía en el listado. Se toma de la primera no pagada por
                 // número, la misma regla que aplica `PaymentPlanSummary`: se cobra lo más viejo.
                 + "       (SELECT x.total_amount FROM credit_portfolio.installments x "
                 + "         WHERE x.schedule_id = i.schedule_id AND x.status <> 'PAID' "
                 + "         ORDER BY x.installment_number LIMIT 1) AS next_amount "
                 + "  FROM credit_portfolio.installments i "
                 + " WHERE schedule_id IN (:scheduleIds) "
                 + " GROUP BY schedule_id", nativeQuery = true)
    List<Object[]> planSummariesByScheduleIds(@Param("scheduleIds") java.util.Collection<UUID> scheduleIds);

    /**
     * Cobro por periodo, agregado en la base (una fila para todo el tablero):
     *  - a cobrar el próximo periodo = parcialidades no pagadas con vencimiento en [nextStart, nextEnd].
     *  - esperado / cobrado del periodo actual = vencimiento en [curStart, curEnd], total vs PAID.
     * Sobre la tabla completa: una cuenta liquidada no tiene parcialidades futuras sin pagar, así que
     * no infla "a cobrar"; el % de pago del periodo cuenta lo liquidado como cobrado, que es correcto.
     */
    @Query(value = "SELECT "
                 + "  COALESCE(SUM(total_amount) FILTER (WHERE status <> 'PAID' AND due_date BETWEEN :nextStart AND :nextEnd), 0) AS a_cobrar_proximo, "
                 + "  COALESCE(SUM(total_amount) FILTER (WHERE due_date BETWEEN :curStart AND :curEnd), 0) AS esperado_actual, "
                 + "  COALESCE(SUM(total_amount) FILTER (WHERE status = 'PAID' AND due_date BETWEEN :curStart AND :curEnd), 0) AS cobrado_actual "
                 + "  FROM credit_portfolio.installments", nativeQuery = true)
    Object[] periodCollectionRow(@Param("curStart") LocalDate curStart, @Param("curEnd") LocalDate curEnd,
                                 @Param("nextStart") LocalDate nextStart, @Param("nextEnd") LocalDate nextEnd);

    @Modifying
    @Query("UPDATE Installment i SET i.dueDate = :newDueDate "
         + "WHERE i.scheduleId = :scheduleId AND i.installmentNumber = :installmentNumber")
    void shiftDueDate(@Param("scheduleId") UUID scheduleId,
                       @Param("installmentNumber") int installmentNumber,
                       @Param("newDueDate") LocalDate newDueDate);
}
