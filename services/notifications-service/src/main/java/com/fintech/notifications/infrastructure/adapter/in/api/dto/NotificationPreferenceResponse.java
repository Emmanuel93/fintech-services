package com.fintech.notifications.infrastructure.adapter.in.api.dto;

import com.fintech.notifications.domain.NotificationPreference;

import java.time.Instant;
import java.util.UUID;

public record NotificationPreferenceResponse(
        UUID partyId,
        String pushToken,
        String whatsappNumber,
        Instant updatedAt
) {
    public static NotificationPreferenceResponse from(NotificationPreference p) {
        return new NotificationPreferenceResponse(p.getPartyId(), p.getPushToken(), p.getWhatsappNumber(), p.getUpdatedAt());
    }
}
