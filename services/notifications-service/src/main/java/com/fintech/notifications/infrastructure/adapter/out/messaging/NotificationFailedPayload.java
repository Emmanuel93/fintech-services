package com.fintech.notifications.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/** No se pudo entregar. Mismo {@code sourceEventId} que el envío, para que el emisor lo reconozca. */
public record NotificationFailedPayload(
        UUID notificationId,
        UUID recipientId,
        String sourceEventId,
        String eventType,
        String channel,
        String failureReason,
        Instant sentAt
) {}
