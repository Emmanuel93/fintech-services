package com.fintech.closing.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code credit-portfolio.day-closed}: cartera cerró el día (mora calculada al día siguiente).
 * {@code heldAccounts}: las cuentas que cargos dejó fuera del lote; su corte se retiene.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DayClosedMessage(LocalDate businessDate, LocalDate delinquencyAsOf, int accounts, int errors,
                               List<UUID> heldAccounts) {

    public List<UUID> heldAccountsOrEmpty() { return heldAccounts == null ? List.of() : heldAccounts; }
}
