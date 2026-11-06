package com.fintech.creditportfolio.domain.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code credit-portfolio.day-closed}: cartera cerró el día {@code businessDate}. La mora quedó
 * calculada al {@code delinquencyAsOf} (el día siguiente, desde las 00:00) y se avisaron las cuotas
 * próximas y vencidas. Con este aviso closing corta el día.
 *
 * <p>{@code heldAccounts}: las cuentas que cargos dejó fuera del lote del día —en cuarentena o retenidas—.
 * Closing retiene su corte hasta que devenguen ({@code credit-portfolio.account-day-closed}).
 */
public record DayClosedEvent(LocalDate businessDate, LocalDate delinquencyAsOf, int accounts, int errors,
                             Instant completedAt, List<UUID> heldAccounts) {

    public DayClosedEvent(LocalDate businessDate, LocalDate delinquencyAsOf, int accounts, int errors,
                          Instant completedAt) {
        this(businessDate, delinquencyAsOf, accounts, errors, completedAt, List.of());
    }
}
