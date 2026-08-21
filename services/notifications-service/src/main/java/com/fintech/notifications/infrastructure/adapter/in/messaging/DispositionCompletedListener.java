package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DispositionCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionCompletedListener.class);
    private final NotificationTriggerService triggerService;

    public DispositionCompletedListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "credit-portfolio.disposition-completed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "dispositionCompletedListenerContainerFactory")
    public void onMessage(DispositionCompletedPayload p) {
        log.debug("disposition-completed creditAccountId={} type={} amount={}",
                p.creditAccountId(), p.dispositionType(), p.amount());
        triggerService.onDispositionCompleted(p.dispositionId().toString(), p.creditAccountId(),
                p.obligorPartyId(), p.amount());
    }
}
