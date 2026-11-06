package com.fintech.closing.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** La corrida de corte terminó: {@code SEALED}, o {@code FAILED} con unidades que revisar. */
public record CutoffRunCompletedPayload(
        String eventId,
        UUID runId,
        LocalDate businessDate,
        String status,
        int plannedUnits,
        int doneUnits,
        int failedUnits,
        int skippedUnits,
        Instant occurredOn,
        /** Cuentas cuyo corte se retiene hasta que llegue su interés; se cortan después, dentro de la misma corrida. */
        int heldUnits
) {}
