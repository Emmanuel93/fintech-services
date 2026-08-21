package com.fintech.notifications.infrastructure.adapter.out.messaging;

import com.fintech.notifications.application.port.out.NotificationEventPublisher;
import com.fintech.notifications.domain.NotificationRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaNotificationEventPublisher implements NotificationEventPublisher {

    static final String TOPIC_SENT   = "notifications.notification-sent";
    static final String TOPIC_FAILED = "notifications.notification-failed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaNotificationEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishNotificationSent(NotificationRecord r) {
        var payload = new NotificationSentPayload(r.getNotificationId(), r.getRecipientId(),
                r.getSourceEventId(), r.getEventType().name(), r.getChannel().name(), r.getSentAt());
        kafkaTemplate.send(TOPIC_SENT, r.getRecipientId().toString(), payload);
    }

    @Override
    public void publishNotificationFailed(NotificationRecord r) {
        var payload = new NotificationFailedPayload(r.getNotificationId(), r.getRecipientId(),
                r.getSourceEventId(), r.getEventType().name(), r.getChannel().name(),
                r.getFailureReason(), r.getSentAt());
        kafkaTemplate.send(TOPIC_FAILED, r.getRecipientId().toString(), payload);
    }
}
