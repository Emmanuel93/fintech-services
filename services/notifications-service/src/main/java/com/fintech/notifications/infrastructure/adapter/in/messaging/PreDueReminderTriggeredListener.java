package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PreDueReminderTriggeredListener {

    private static final Logger log = LoggerFactory.getLogger(PreDueReminderTriggeredListener.class);
    private final NotificationTriggerService triggerService;

    public PreDueReminderTriggeredListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "collections.pre-due-reminder-triggered",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "preDueReminderTriggeredListenerContainerFactory")
    public void onMessage(PreDueReminderTriggeredPayload p) {
        log.debug("pre-due-reminder-triggered creditAccountId={} dueDate={}", p.creditAccountId(), p.dueDate());
        // Sin eventId propio en el payload — compuesto estable por ciclo de vencimiento (idempotencia).
        String sourceEventId = "predue:" + p.creditAccountId() + ":" + p.dueDate();
        triggerService.onPreDueReminderTriggered(sourceEventId, p.creditAccountId(), p.obligorPartyId(),
                p.dueDate(), p.installmentAmount());
    }
}
