package com.fintech.notifications.infrastructure.adapter.out.persistence;

import com.fintech.notifications.application.port.out.NotificationRecipientRepository;
import com.fintech.notifications.domain.NotificationRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataNotificationRecipientRepository
        extends JpaRepository<NotificationRecipient, NotificationRecipient.Key> {}

@Repository
class JpaNotificationRecipientAdapter implements NotificationRecipientRepository {

    private final SpringDataNotificationRecipientRepository jpa;

    JpaNotificationRecipientAdapter(SpringDataNotificationRecipientRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<NotificationRecipient> find(String recipientType, UUID recipientId) {
        if (recipientType == null || recipientId == null) return Optional.empty();
        return jpa.findById(new NotificationRecipient.Key(
                recipientType.trim().toUpperCase(), recipientId));
    }

    @Override
    public NotificationRecipient save(NotificationRecipient recipient) {
        return jpa.save(recipient);
    }
}
