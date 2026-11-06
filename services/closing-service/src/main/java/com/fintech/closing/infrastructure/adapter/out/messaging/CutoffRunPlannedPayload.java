package com.fintech.closing.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * La corrida de corte quedó planificada: cuántas cuentas entran, por producto y por titular.
 *
 * <p>Es la cifra contra la que se mide el avance, del corte y de los estados de cuenta.
 */
public record CutoffRunPlannedPayload(
        String eventId,
        UUID runId,
        LocalDate businessDate,
        int totalUnits,
        List<Line> breakdown,
        Instant occurredOn
) {
    public record Line(String productType, String productBehavior, String holderKind, int units) {}
}
