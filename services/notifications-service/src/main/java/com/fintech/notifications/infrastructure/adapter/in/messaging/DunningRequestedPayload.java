package com.fintech.notifications.infrastructure.adapter.in.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code collections.dunning-requested} — cobranza pide que se le escriba a un deudor. */
public record DunningRequestedPayload(
        UUID caseId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String step,
        int stepNumber,
        int daysDelinquent,
        String bucket,
        BigDecimal totalDebt,
        Instant occurredAt
) {}
