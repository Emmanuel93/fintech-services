package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DunningRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(DunningRequestedListener.class);
    private final NotificationTriggerService triggerService;

    public DunningRequestedListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "collections.dunning-requested",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "dunningRequestedListenerContainerFactory")
    public void onMessage(DunningRequestedPayload p) {
        log.debug("dunning-requested caseId={} step={} days={}", p.caseId(), p.step(), p.daysDelinquent());
        // El id de origen lleva el caso y el escalón: es lo que permite a cobranza reconocer el
        // desenlace del envío y apuntarlo en el expediente correcto. Y como es estable por caso y
        // escalón, dos corridas del mismo día no producen dos mensajes.
        String sourceEventId = "dunning:" + p.caseId() + ":" + p.stepNumber();
        triggerService.onDunningRequested(sourceEventId, p.caseId(), p.obligorPartyId(),
                p.step(), p.daysDelinquent(), p.totalDebt());
    }
}
