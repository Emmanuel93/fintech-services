package com.fintech.closing.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * El corte sellado, que cartera y wallet proyectan de vuelta y del que sale el estado de cuenta.
 *
 * <p>{@code amountDue} y {@code minimumPayment} vienen nulos para productos a plazo: ese importe
 * sale del plan de amortización, que es de cartera. El cierre no lo inventa.
 *
 * <p>Lo que se agregó para el estado de cuenta —corrida, comportamiento, titular, inicio del período
 * y desglose— es aditivo: los consumidores existentes ignoran lo que no conocen.
 */
public record CutoffClosedPayload(
        String eventId,
        UUID runId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String productType,
        String productBehavior,
        String holderKind,
        int cycleNumber,
        LocalDate periodStart,
        LocalDate cutoffDate,
        LocalDate paymentDueDate,
        BigDecimal balanceAtCutoff,
        BigDecimal principalAtCutoff,
        BigDecimal interestAtCutoff,
        BigDecimal amountDue,
        BigDecimal minimumPayment,
        int daysDelinquent,
        Instant occurredOn
) {}
