package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CreditAccountActivatedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountActivatedListener.class);
    private final NotificationTriggerService triggerService;

    public CreditAccountActivatedListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "credit-portfolio.credit-account-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountActivatedListenerContainerFactory")
    public void onMessage(CreditAccountActivatedPayload p) {
        log.debug("credit-account-activated creditAccountId={} obligorPartyId={}", p.creditAccountId(), p.obligorPartyId());
        triggerService.onCreditAccountActivated(p.eventId(), p.creditAccountId(), p.obligorPartyId(),
                p.contractId(), p.productType(), p.creditLimit(), p.nominalRate());
    }
}
