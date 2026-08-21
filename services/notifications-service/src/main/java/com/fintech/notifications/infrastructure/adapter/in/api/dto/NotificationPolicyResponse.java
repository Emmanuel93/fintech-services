package com.fintech.notifications.infrastructure.adapter.in.api.dto;

import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.NotificationPolicy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationPolicyResponse(
        UUID policyId,
        String eventType,
        /** La llave real. `eventType` sólo viene si la clave está en el catálogo del crédito. */
        String eventKey,
        String valueTier,
        String channelStrategy,
        String primaryChannel,
        List<NotificationChannel> fallbackChannels,
        int version,
        String status,
        Instant createdAt
) {
    public static NotificationPolicyResponse from(NotificationPolicy p) {
        return new NotificationPolicyResponse(p.getPolicyId(),
                // Nulo cuando la clave viene de un emisor externo al catálogo: es lo esperado,
                // no un dato faltante.
                p.getEventType() == null ? null : p.getEventType().name(),
                p.getEventKey(), p.getValueTier().name(),
                p.getChannelStrategy().name(), p.getPrimaryChannel().name(), p.getFallbackChannels(),
                p.getVersion(), p.getStatus().name(), p.getCreatedAt());
    }
}
