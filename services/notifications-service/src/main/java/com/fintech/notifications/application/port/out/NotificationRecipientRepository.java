package com.fintech.notifications.application.port.out;

import com.fintech.notifications.domain.NotificationRecipient;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRecipientRepository {
    Optional<NotificationRecipient> find(String recipientType, UUID recipientId);
    NotificationRecipient save(NotificationRecipient recipient);
}
