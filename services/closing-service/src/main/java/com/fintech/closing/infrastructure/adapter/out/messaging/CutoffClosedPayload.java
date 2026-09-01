package com.fintech.closing.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * El corte sellado, que cartera y wallet proyectan de vuelta.
 *
 * <p>{@code amountDue} y {@code minimumPayment} vienen nulos para productos a plazo: ese importe
 * sale del plan de amortización, que es de cartera. El cierre no lo inventa.
 */
public record CutoffClosedPayload(
        String eventId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String productType,
        int cycleNumber,
        LocalDate cutoffDate,
        LocalDate paymentDueDate,
        BigDecimal balanceAtCutoff,
        BigDecimal amountDue,
        BigDecimal minimumPayment,
        Instant occurredOn
) {}
