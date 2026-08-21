package com.fintech.notifications.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Salió un mensaje.
 *
 * <p>{@code sourceEventId} viaja para que quien pidió el envío pueda reconocer su propio encargo.
 * Es el identificador con el que llegó la petición —cobranza usa {@code dunning:{caseId}:{paso}}—
 * y devolverlo tal cual evita que notifications tenga que conocer los conceptos de cada dominio que
 * le pide mensajes: el emisor lo interpreta, aquí sólo se acarrea.
 */
public record NotificationSentPayload(
        UUID notificationId,
        UUID recipientId,
        String sourceEventId,
        String eventType,
        String channel,
        Instant sentAt
) {}
