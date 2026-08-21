package com.fintech.notifications.application.port.in;

import com.fintech.notifications.domain.NotificationPreference;

import java.util.Optional;
import java.util.UUID;

public interface ManageNotificationPreferenceUseCase {
    Optional<NotificationPreference> get(UUID partyId);
    NotificationPreference update(UUID partyId, String pushToken, String whatsappNumber);
}
