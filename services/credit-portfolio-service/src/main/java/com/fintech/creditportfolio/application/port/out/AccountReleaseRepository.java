package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.application.port.out.DayCloseRepository.ChargesTotals;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Las cuentas que devengaron tarde ({@code charges.account-accrued}) y si cartera ya las soltó a closing.
 * Un aviso repetido no duplica; una cuenta soltada no se vuelve a esperar.
 */
public interface AccountReleaseRepository {

    /** Anota lo que cargos dijo que devengó la cuenta ese día. Idempotente. */
    void record(LocalDate businessDate, UUID creditAccountId, ChargesTotals totals);

    Optional<Release> find(LocalDate businessDate, UUID creditAccountId);

    /** Las que esperan, en orden de día y de cuenta. */
    List<Release> pending(int limit);

    void waiting(LocalDate businessDate, UUID creditAccountId, String reason);

    void released(LocalDate businessDate, UUID creditAccountId);

    record Release(LocalDate businessDate, UUID creditAccountId, ChargesTotals totals, String status, String waitingFor) {
        public boolean released() { return "RELEASED".equals(status); }
    }
}
