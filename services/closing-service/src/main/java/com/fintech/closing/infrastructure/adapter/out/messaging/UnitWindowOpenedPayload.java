package com.fintech.closing.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** «La ventana de esta fase, para esta cuenta, en esta fecha de negocio, está abierta.» */
public record UnitWindowOpenedPayload(
        String eventId,
        UUID runId,
        UUID creditAccountId,
        String phase,
        LocalDate businessDate,
        String productType,
        Instant occurredOn
) {}
