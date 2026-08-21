package com.fintech.collections.application;

import com.fintech.collections.domain.ContactChannel;
import com.fintech.collections.domain.ContactOrigin;
import com.fintech.collections.domain.ContactResult;
import com.fintech.collections.domain.DelinquencyBucket;

import java.time.Instant;
import java.util.UUID;

/**
 * Una fila de la bandeja de contactos: el intento más el contexto de su caso.
 *
 * <p>Mismo criterio que {@link PromiseQueueRow}: el tramo y el gestor son del caso y se traen en
 * el {@code JOIN}, no con una llamada por fila.
 */
public record ContactQueueRow(
        UUID attemptId,
        UUID caseId,
        ContactChannel channel,
        ContactResult result,
        ContactOrigin origin,
        String agentId,
        Integer dunningStep,
        Instant attemptedAt,
        // ── Contexto del caso ────────────────────────────────────────────────
        UUID creditAccountId,
        UUID obligorPartyId,
        String productType,
        DelinquencyBucket currentBucket,
        int daysDelinquent,
        String assignedAgentId) {}
