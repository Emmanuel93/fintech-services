package com.fintech.notifications.application.port.in;

import java.util.UUID;

public interface ManageNotificationReadStateUseCase {
    /** @return false si notificationId no existe. */
    boolean markAsRead(UUID notificationId);

    /** @return cuántas notificaciones se marcaron (antes no leídas). */
    int markAllAsRead(UUID recipientId);
}
