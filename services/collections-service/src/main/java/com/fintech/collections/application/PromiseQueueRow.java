package com.fintech.collections.application;

import com.fintech.collections.domain.DelinquencyBucket;
import com.fintech.collections.domain.ContactResult;
import com.fintech.collections.domain.PromiseStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Una fila de la bandeja de promesas: la promesa más el contexto de su caso.
 *
 * <p>Trae el tramo y el gestor porque son los filtros de la bandeja y viven en el caso, no en la
 * promesa. Trae {@code attemptsToday} y {@code lastContactResult} porque son lo que el gestor mira
 * antes de marcar: si ya se le llamó tres veces hoy, la siguiente llamada no es gestión sino
 * hostigamiento, y el sistema tiene que poder decirlo antes de que alguien levante el teléfono.
 *
 * <p>Todo sale de una sola consulta con {@code JOIN} y dos subconsultas correlacionadas. Es
 * deliberado: resolverlo con una llamada por fila desde el BFF es exactamente lo que el invariante
 * prohíbe, y hacerlo en el dueño cuesta un índice, no una arquitectura.
 */
public record PromiseQueueRow(
        UUID promiseId,
        UUID caseId,
        BigDecimal amount,
        LocalDate promisedDate,
        PromiseStatus status,
        String recordedBy,
        Instant createdAt,
        // ── Contexto del caso ────────────────────────────────────────────────
        UUID creditAccountId,
        UUID obligorPartyId,
        String productType,
        DelinquencyBucket currentBucket,
        int daysDelinquent,
        String assignedAgentId,
        // ── Gestión ──────────────────────────────────────────────────────────
        long attemptsToday,
        ContactResult lastContactResult) {}
