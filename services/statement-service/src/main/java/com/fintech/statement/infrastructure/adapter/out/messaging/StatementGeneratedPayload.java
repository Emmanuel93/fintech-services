package com.fintech.statement.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * El estado de cuenta existe y se puede descargar. Lo que cualquier consumidor necesita para avisar
 * o proyectar sin pedir el documento: a quién, de qué período, cuánto y para cuándo.
 */
public record StatementGeneratedPayload(
        String eventId,
        UUID statementId,
        UUID runId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String holderKind,
        String productType,
        String layout,
        int cycleNumber,
        LocalDate periodStart,
        LocalDate cutoffDate,
        LocalDate paymentDueDate,
        BigDecimal balanceAtCutoff,
        BigDecimal amountDue,
        BigDecimal minimumPayment,
        String documentPath,
        Instant occurredOn
) {}
