package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.AccountCloseProfile;
import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseRun;
import com.fintech.closing.domain.CloseSeal;
import com.fintech.closing.domain.CutoffSchedule;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Lo que el cierre publica. <b>Hechos, no comandos.</b>
 *
 * <p>El repo tiene esa regla explícita y se preserva: {@code unit-window-opened} no dice «devenga
 * esta cuenta», dice «la ventana de la fase ACCRUAL para esta unidad, en la fecha de negocio D,
 * está abierta». Cada dominio reacciona con su propia lógica y su propia idempotencia, igual que
 * hoy reacciona a {@code balance-updated}.
 */
public interface ClosingEventPublisher {

    void publishUnitWindowOpened(UUID runId, UUID creditAccountId, ClosePhase phase,
                                  LocalDate businessDate, String productType);

    /**
     * El corte sellado, que cartera y wallet proyectan de vuelta y del que sale el estado de cuenta.
     *
     * @param runId          la corrida que lo cortó; nula si el corte no vino de una corrida
     * @param periodStart    el primer día del período que este corte cierra
     * @param holderKind     {@code CUSTOMER} o {@code DISTRIBUTOR}
     * @param daysDelinquent days past due as of the cutoff date, from the portfolio's snapshot of that day
     */
    void publishCutoffClosed(CutoffSchedule cutoff, AccountCloseProfile profile, UUID runId,
                             LocalDate periodStart, String holderKind, int daysDelinquent);

    /**
     * «A esta cuenta le toca su corte en esta corrida.» Es el reparto: un mensaje por cuenta, con la
     * cuenta como llave, y cada pod del grupo toma las de sus particiones.
     *
     * <p>Devuelve el futuro del envío porque el despacho sólo marca como enviada la unidad que el
     * broker confirmó: una que se pierda en el camino vuelve a salir en la siguiente vuelta.
     */
    CompletableFuture<Void> publishCutoffDue(CutoffUnitStore.Dispatchable unit, LocalDate businessDate);

    /** La corrida de corte quedó planificada: cuántas cuentas entran, por producto y por titular. */
    void publishCutoffRunPlanned(CloseRun run, List<CutoffUnitStore.Breakdown> breakdown);

    /** La corrida de corte terminó: selló, o quedó con fallidas que revisar. */
    void publishCutoffRunCompleted(CloseRun run, CutoffUnitStore.StatusCounts counts);

    void publishDaySealed(CloseSeal seal);
}
