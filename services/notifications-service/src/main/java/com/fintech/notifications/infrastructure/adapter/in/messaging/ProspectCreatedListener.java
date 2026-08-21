package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ProspectCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(ProspectCreatedListener.class);
    private final NotificationTriggerService triggerService;

    public ProspectCreatedListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "origination.prospect-created",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "prospectCreatedListenerContainerFactory")
    public void onMessage(ProspectCreatedPayload p) {
        log.debug("prospect-created prospectId={}", p.prospectId());
        triggerService.onProspectCreated(p.prospectId(), p.firstName(), p.phone(), p.email());
    }
}
