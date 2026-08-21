package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Alguien pagó lo que prometió. El único mensaje del módulo que no pide nada.
 *
 * <p>{@code daysDelinquent} viaja para que la plantilla distinga los dos desenlaces: si quedó en
 * cero, el mensaje cierra —«quedaste al corriente»—; si queda saldo, agradece sin dar por resuelto
 * lo que no lo está.
 */
public record PaymentThanksPayload(
        UUID caseId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal amount,
        int daysDelinquent,
        Instant occurredAt
) {}
