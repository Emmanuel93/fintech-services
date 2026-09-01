package com.fintech.banking.application.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Avisa de un descuadre entre el mayor y el banco.
 *
 * <p>Existe como puerto y no como un {@code log.error} porque el análisis encontró que
 * {@code publishReconciliationAlert} ya estaba declarado en contabilidad y <b>no lo invocaba ni una
 * línea</b>. Una alerta sin emisor y una sin consumidor son el mismo problema con distinto disfraz.
 */
public interface ReconciliationAlertPublisher {

    void publicarDescuadre(UUID bankAccountId, LocalDate businessDate,
                           BigDecimal saldoContable, BigDecimal saldoDelBanco,
                           BigDecimal partidasEnConciliacion, BigDecimal diferencia,
                           int partidasAbiertas);
}
