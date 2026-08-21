package com.fintech.notifications.infrastructure.adapter.out.persistence;

import com.fintech.notifications.application.port.out.NotificationPreferenceRepository;
import com.fintech.notifications.domain.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaNotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, UUID>, NotificationPreferenceRepository {
}
