package com.fintech.creditportfolio.application.port.out;

import java.math.BigDecimal;

/**
 * Agregados de la cartera para el tablero del backoffice.
 *
 * <p>Se calculan <b>en la base</b>, no trayendo las cuentas a memoria: el
 * tablero es la primera pantalla que ve cualquier usuario del backoffice y la
 * cartera crece sin techo. Sumar en Java obliga a leer cada fila en cada carga
 * de cada usuario, que es exactamente lo que revienta cuando hay volumen.
 */
public record PortfolioSummary(
        long activeAccounts,
        long activeObligors,
        BigDecimal principalBalance,
        BigDecimal totalDebt,
        /**
         * Escala IFRS-9 por días de atraso: stage1 ≤30 (al corriente), stage2 31–90 (SICR, atraso
         * temprano), stage3 &gt;90 (deteriorado). La <b>cartera vencida oficial (CNBV/IMOR)</b> es
         * stage3 — 90+ días —, no stage2+stage3; el tramo 31–90 es atraso, no cartera vencida.
         */
        BigDecimal stage1Principal,
        BigDecimal stage2Principal,
        BigDecimal stage3Principal,
        long delinquentAccounts,
        /** Suma de parcialidades no pagadas con vencimiento en el próximo periodo (mes) — a cobrar. */
        BigDecimal aCobrarProximoPeriodo,
        /** Total esperado del periodo actual (parcialidades con vencimiento este mes). */
        BigDecimal esperadoPeriodoActual,
        /** De lo esperado del periodo actual, lo ya cobrado (parcialidades PAID). */
        BigDecimal cobradoPeriodoActual
) {}
