package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseSeal;
import com.fintech.closing.domain.CutoffSchedule;

import java.time.LocalDate;
import java.util.UUID;

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

    /** El corte sellado, que cartera y wallet proyectan de vuelta. */
    void publishCutoffClosed(CutoffSchedule cutoff, UUID obligorPartyId, String productType);

    void publishDaySealed(CloseSeal seal);
}
