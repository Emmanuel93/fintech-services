package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pide que se le escriba a un deudor. No dice por dónde ni con qué texto.
 *
 * <p>El canal lo elige notifications con su política y las preferencias del cliente, y el texto sale
 * de su plantilla. Cobranza sólo aporta lo que sólo ella sabe: a quién, en qué escalón de tono, y
 * las cifras que la plantilla necesita.
 *
 * <p>{@code caseId} viaja para que el desenlace del envío pueda volver al caso: sin él, notifications
 * confirmaría una entrega que cobranza no sabría dónde apuntar.
 */
public record DunningRequestedPayload(
        UUID caseId,
        UUID creditAccountId,
        UUID obligorPartyId,
        /** RECORDATORIO | COMO_PAGAR | ATRASO | HISTORIAL | OFRECER_AYUDA */
        String step,
        int stepNumber,
        int daysDelinquent,
        String bucket,
        BigDecimal totalDebt,
        Instant occurredAt
) {}
