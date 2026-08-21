package com.fintech.creditportfolio.application.port.out;

import java.math.BigDecimal;

/**
 * Conteo y capital de la cartera agrupados por una dimensión
 * (estado, tipo de producto o bucket de días de atraso).
 *
 * <p>Se agrega <b>en la base</b>: el tablero pinta estas distribuciones y leer
 * toda la cartera para contarla en Java es justo lo que no escala.
 */
public record PortfolioStat(
        String key,
        long count,
        BigDecimal principal
) {}
