package com.fintech.notifications.application.port.out;

import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.NotificationRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRecordRepository {
    boolean existsBySourceEventIdAndChannel(String sourceEventId, NotificationChannel channel);
    List<NotificationRecord> findByRecipientId(UUID recipientId);

    /** El buzón de una entidad, lo más reciente primero. */
    List<NotificationRecord> findFeed(String recipientType, UUID recipientId);

    /** Sólo lo no leído: es el número del indicador de la campana. */
    long countUnread(String recipientType, UUID recipientId);

    int markAllRead(String recipientType, UUID recipientId);
    Optional<NotificationRecord> findById(UUID notificationId);
    NotificationRecord save(NotificationRecord record);

    /** Update atómico — marca como leídas todas las notificaciones aún no leídas del destinatario. */
    int markAllReadByRecipientId(UUID recipientId);
}
