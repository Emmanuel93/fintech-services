package com.fintech.notifications.application.port.out;

import com.fintech.notifications.domain.NotificationRecord;

/**
 * {@code NotificationDelivered} no se publica en v1 — requeriría un webhook de confirmación real
 * del proveedor (FCM/WhatsApp Cloud API), que no existe mientras los adaptadores sean Noop.
 */
public interface NotificationEventPublisher {
    void publishNotificationSent(NotificationRecord record);
    void publishNotificationFailed(NotificationRecord record);
}
