package com.fintech.closing.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Los días que cartera cerró y qué hizo closing con cada uno. Es la compuerta del corte —sin el cierre
 * de cartera de D no se planifica el corte de D— y lo que dice por qué un día no se ha cortado.
 */
public interface PortfolioDayCloseLog {

    /** Cartera cerró el día, con las cuentas que cargos dejó fuera. Idempotente: el aviso puede repetirse. */
    void record(LocalDate businessDate, LocalDate delinquencyAsOf, int accounts, int errors, List<UUID> heldAccounts);

    /** Las cuentas de ese día cuyo corte se retiene: fuera del lote de cargos y todavía no soltadas. */
    List<UUID> heldAccounts(LocalDate businessDate);

    /** Cartera soltó la cuenta de ese día. Idempotente. */
    void recordRelease(LocalDate businessDate, UUID creditAccountId);

    boolean closed(LocalDate businessDate);

    /** Días cerrados por cartera cuyo corte no se ha planificado, del más viejo al más nuevo. */
    List<LocalDate> pendingCutoffs();

    void waiting(LocalDate businessDate, String reason);

    void planned(LocalDate businessDate);

    /** Días cuyo corte terminó (sellado o fallido) y cuyo aviso de fin no ha salido. */
    List<LocalDate> completedNotAnnounced();

    void completionAnnounced(LocalDate businessDate);

    Optional<String> waitingFor(LocalDate businessDate);
}
