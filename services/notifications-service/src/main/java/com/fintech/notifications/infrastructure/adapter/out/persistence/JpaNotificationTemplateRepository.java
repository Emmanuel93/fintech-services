package com.fintech.notifications.infrastructure.adapter.out.persistence;

import com.fintech.notifications.application.port.out.NotificationTemplateRepository;
import com.fintech.notifications.domain.EventType;
import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaNotificationTemplateRepository
        extends JpaRepository<NotificationTemplate, UUID>, NotificationTemplateRepository {

    @Override
    Optional<NotificationTemplate> findByEventTypeAndChannelAndLocale(
            EventType eventType, NotificationChannel channel, String locale);

    @Override
    Optional<NotificationTemplate> findByEventKeyAndChannelAndLocale(
            String eventKey, NotificationChannel channel, String locale);
}
