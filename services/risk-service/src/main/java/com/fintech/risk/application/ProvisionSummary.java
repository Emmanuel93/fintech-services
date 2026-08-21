package com.fintech.risk.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Rollup de reserva vigente para Finanzas/Auditoría (y futuro consumo de T4): total + desglose por
 * (productType, ifrs9Stage). Se calcula al leer, no se almacena — evita duplicar fuente de verdad.
 */
public record ProvisionSummary(
        BigDecimal totalProvision,
        BigDecimal totalEad,
        long activeProfiles,
        List<Row> breakdown
) {
    public record Row(String productType, String ifrs9Stage, long count,
                      BigDecimal totalEad, BigDecimal totalProvision) {}
}
