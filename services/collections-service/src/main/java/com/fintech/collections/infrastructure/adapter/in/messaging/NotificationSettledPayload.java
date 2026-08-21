package com.fintech.collections.infrastructure.adapter.in.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * El desenlace de un mensaje, entregado o fallido.
 *
 * <p>Un solo tipo para los dos tópicos: los payloads de notifications se diferencian sólo en
 * {@code failureReason}, y tener dos records idénticos salvo un campo obligaría a duplicar el
 * listener sin ganar nada. Los campos que no vengan quedan nulos, que es lo que ya significan.
 */
public record NotificationSettledPayload(
        UUID notificationId,
        UUID recipientId,
        String sourceEventId,
        String eventType,
        String channel,
        String failureReason,
        Instant sentAt
) {}
