package com.fintech.creditportfolio.application.port.out;

import java.math.BigDecimal;

/** Cartera activa agrupada por producto, para la distribución del tablero. */
public record ProductMix(
        String productType,
        String productBehavior,
        long accounts,
        BigDecimal capital,
        BigDecimal overdueCapital
) {}
