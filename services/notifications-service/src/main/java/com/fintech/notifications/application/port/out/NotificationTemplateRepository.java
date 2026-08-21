package com.fintech.notifications.application.port.out;

import com.fintech.notifications.domain.EventType;
import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.NotificationTemplate;

import java.util.Optional;

public interface NotificationTemplateRepository {
    Optional<NotificationTemplate> findByEventTypeAndChannelAndLocale(
            EventType eventType, NotificationChannel channel, String locale);

    /** La plantilla de una clave de evento, venga del catálogo interno o de un emisor externo. */
    Optional<NotificationTemplate> findByEventKeyAndChannelAndLocale(
            String eventKey, NotificationChannel channel, String locale);
}
