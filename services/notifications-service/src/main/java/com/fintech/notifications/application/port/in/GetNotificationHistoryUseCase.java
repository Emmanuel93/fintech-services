package com.fintech.notifications.application.port.in;

import com.fintech.notifications.domain.NotificationRecord;

import java.util.List;
import java.util.UUID;

public interface GetNotificationHistoryUseCase {
    List<NotificationRecord> getByRecipientId(UUID recipientId);
}
