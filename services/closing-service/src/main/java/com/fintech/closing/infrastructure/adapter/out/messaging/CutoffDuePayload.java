package com.fintech.closing.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * «A esta cuenta le toca su corte en esta corrida.» Un mensaje por cuenta, con la cuenta como llave.
 *
 * <p>Es un hecho y no una orden, como {@code unit-window-opened}: dice qué unidad quedó abierta, y
 * el consumidor decide con el CAS si de verdad le toca trabajarla.
 */
public record CutoffDuePayload(
        String eventId,
        UUID runId,
        UUID unitId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String productType,
        String productBehavior,
        String holderKind,
        LocalDate businessDate,
        Instant occurredOn
) {}
