package com.fintech.closing.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Los movimientos de saldo de cada cuenta con su fecha valor. Con ellos el corte de D se sella con el
 * saldo al cierre de D: el de la proyección menos lo que pertenece a días posteriores.
 */
public interface BalanceMovementRepository {

    /** Idempotente por (cuenta, versión). */
    void record(UUID creditAccountId, long balanceVersion, LocalDate valueDate,
                BigDecimal principalDelta, BigDecimal debtDelta);

    /** Lo que movieron el capital y la deuda los hechos con fecha valor posterior a {@code date}. */
    Later after(UUID creditAccountId, LocalDate date);

    /** Borra lo que ya ningún corte va a mirar. */
    int pruneBefore(LocalDate date);

    record Later(BigDecimal principal, BigDecimal debt) {
        public static final Later NONE = new Later(BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
