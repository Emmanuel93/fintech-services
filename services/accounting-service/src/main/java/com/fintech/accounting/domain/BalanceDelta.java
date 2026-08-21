package com.fintech.accounting.domain;

import java.math.BigDecimal;

/** Cambio de saldos entre dos balance-updated consecutivos — de aquí sale el monto de cada asiento. */
public record BalanceDelta(
        BigDecimal principalDelta,
        BigDecimal interestDelta,
        BigDecimal penaltyDelta,
        BigDecimal totalDelta
) {
    public BigDecimal absTotal() { return totalDelta.abs(); }
}
