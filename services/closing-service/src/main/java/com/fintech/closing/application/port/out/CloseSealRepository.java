package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.ClosePhase;
import com.fintech.closing.domain.CloseSeal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface CloseSealRepository {
    Optional<CloseSeal> find(LocalDate businessDate, ClosePhase phase, String scopeKey);
    CloseSeal save(CloseSeal seal);

    /** Las cifras de control del día, agregadas en SQL. Recorrer cuentas en memoria no escala. */
    Totales aggregateActive();

    record Totales(int cuentas, BigDecimal principal, BigDecimal interes,
                   BigDecimal penalidad, BigDecimal deudaTotal) {}
}
