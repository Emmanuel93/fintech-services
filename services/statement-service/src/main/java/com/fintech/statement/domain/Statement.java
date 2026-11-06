package com.fintech.statement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un estado de cuenta: uno por cuenta y ciclo. La llave natural (cuenta, ciclo) es lo que hace
 * idempotente todo lo demás: el mismo corte entregado dos veces encuentra el mismo documento.
 */
public record Statement(UUID statementId, UUID creditAccountId, int cycleNumber, UUID runId,
                        UUID obligorPartyId, String holderKind, String productType, String productBehavior,
                        StatementLayout layout, LocalDate periodStart, LocalDate cutoffDate,
                        LocalDate paymentDueDate, BigDecimal balanceAtCutoff, BigDecimal amountDue,
                        BigDecimal minimumPayment, StatementStatus status, int attempts, String lastError,
                        String contractNumber, Long documentBytes, String templateVersion,
                        String generatedBy, Instant generatedAt, Instant createdAt) {}
