package com.fintech.charges.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface ChargeEventPublisher {
    default void publishChargeApplied(String eventId, UUID creditAccountId, String chargeType,
                                      BigDecimal totalAmount) {
        publishChargeApplied(eventId, creditAccountId, chargeType, totalAmount, null);
    }

    /**
     * @param effectiveDate el día al que pertenece el cargo; {@code null} = hoy.
     *
     * <p>Viaja hasta contabilidad, que deriva de él el <b>período</b> de la póliza. Sin este dato, un
     * devengo del 30 de junio procesado hoy se asienta en el período de hoy y la contabilidad no
     * puede tener historia.
     *
     * <p>Espera la confirmación del broker y lanza si no llega: quien llama está dentro de la
     * transacción del cargo, y un cargo que no salió no debe quedar guardado como devengado.
     */
    default void publishChargeApplied(String eventId, UUID creditAccountId, String chargeType,
                                      BigDecimal totalAmount, LocalDate effectiveDate) {
        publishChargeApplied(eventId, creditAccountId, chargeType, totalAmount, effectiveDate, null);
    }

    /**
     * @param dayCloseDate el cierre del día que generó el cargo; {@code null} si no salió de uno. Cartera
     *                     lo guarda y cuenta ese lote para cuadrar el día.
     */
    void publishChargeApplied(String eventId, UUID creditAccountId, String chargeType,
                              BigDecimal totalAmount, LocalDate effectiveDate, LocalDate dayCloseDate);
    void publishChargeReversed(String eventId, UUID creditAccountId, String originalChargeType,
                               BigDecimal amount, boolean waived);

    /**
     * Los cargos del día ya están devengados. Es la señal con la que sigue el cierre: cartera
     * recalcula la mora y después closing corta. Espera la confirmación del broker; si no llega, lanza.
     */
    void publishDayAccrued(DayAccrued day);

    /**
     * El devengo de un día terminó, con sus <b>cifras de control</b>: cuántos cargos de interés
     * ordinario y moratorio salieron y por cuánto. Cartera no cierra el día hasta tenerlos todos.
     */
    record DayAccrued(LocalDate businessDate, int ordinarySchedules, int ordinaryErrors,
                      int moratoriumSchedules, int moratoriumErrors,
                      int ordinaryCharges, BigDecimal ordinaryAmount,
                      int moratoriumCharges, BigDecimal moratoriumAmount,
                      long elapsedMs, java.time.Instant completedAt,
                      int accounts, java.util.List<UUID> heldAccounts) {}

    /**
     * Una cuenta del cierre de un día, al tópico de trabajo ({@code charges.accrual-due}, llave = cuenta).
     * No espera: quien despacha junta las confirmaciones de una página y marca sólo las confirmadas.
     */
    java.util.concurrent.CompletableFuture<?> publishAccrualDue(LocalDate businessDate, UUID creditAccountId);

    /**
     * Una cuenta que había quedado sin devengar —en cuarentena o retenida— ya devengó su día, con sus
     * cifras de control. Cartera cuadra esa cuenta y closing suelta su corte. Espera al broker.
     */
    void publishAccountAccrued(AccountAccrued accrued);

    record AccountAccrued(LocalDate businessDate, UUID creditAccountId, int ordinaryCharges,
                          BigDecimal ordinaryAmount, int moratoriumCharges, BigDecimal moratoriumAmount) {}
}
