package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound to credit-portfolio (topic {@code collections.write-off-executed}).
 * credit-portfolio's existing consumer only reads eventId/creditAccountId (WO-03: zeroes out
 * its own current balances) — the rest is carried for Risk/Audit/future consumers.
 */
record WriteOffExecutedPayload(
        String eventId,
        UUID creditAccountId,
        UUID writeOffId,
        UUID caseId,
        UUID obligorPartyId,
        BigDecimal principalWrittenOff,
        BigDecimal interestWrittenOff,
        BigDecimal penaltyWrittenOff,
        BigDecimal totalWrittenOff,
        String authorizedBy,
        String authorizationRef,
        String reason,
        Instant occurredOn
) {}
