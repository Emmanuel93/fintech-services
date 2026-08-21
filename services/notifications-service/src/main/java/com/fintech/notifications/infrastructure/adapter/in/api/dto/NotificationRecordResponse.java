package com.fintech.notifications.infrastructure.adapter.in.api.dto;

import com.fintech.notifications.domain.NotificationRecord;

import java.time.Instant;
import java.util.UUID;

public record NotificationRecordResponse(
        UUID notificationId,
        UUID recipientId,
        String eventType,
        String eventKey,
        String channel,
        String status,
        String failureReason,
        Instant sentAt,
        boolean isRead
) {
    public static NotificationRecordResponse from(NotificationRecord r) {
        return new NotificationRecordResponse(r.getNotificationId(), r.getRecipientId(),
                r.getEventType() == null ? null : r.getEventType().name(), r.getEventKey(),
                r.getChannel().name(), r.getStatus().name(), r.getFailureReason(), r.getSentAt(), r.isRead());
    }
}
