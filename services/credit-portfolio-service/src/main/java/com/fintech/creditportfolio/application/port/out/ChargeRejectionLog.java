package com.fintech.creditportfolio.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Los cargos que cartera rechazó. Un cargo rechazado también llegó: cuenta para las cifras de control
 * del día, y un reenvío del mismo cargo no se vuelve a rechazar ni a avisar.
 */
public interface ChargeRejectionLog {

    boolean exists(String sourceEventId);

    void record(String sourceEventId, UUID creditAccountId, String chargeType, BigDecimal amount,
                LocalDate valueDate, LocalDate dayCloseDate, String reason);
}
