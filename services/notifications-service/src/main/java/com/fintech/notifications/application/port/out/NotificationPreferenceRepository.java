package com.fintech.notifications.application.port.out;

import com.fintech.notifications.domain.NotificationPreference;

import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferenceRepository {
    Optional<NotificationPreference> findById(UUID partyId);
    NotificationPreference save(NotificationPreference preference);
}
