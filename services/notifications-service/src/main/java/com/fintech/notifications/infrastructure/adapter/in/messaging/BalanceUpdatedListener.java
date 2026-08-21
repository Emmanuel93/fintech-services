package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BalanceUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceUpdatedListener.class);
    private final NotificationTriggerService triggerService;

    public BalanceUpdatedListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "credit-portfolio.balance-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "balanceUpdatedListenerContainerFactory")
    public void onMessage(BalanceUpdatedPayload p) {
        log.debug("balance-updated creditAccountId={} accountStatus={}", p.creditAccountId(), p.accountStatus());
        triggerService.onBalanceUpdated(p.eventId(), p.creditAccountId(), p.obligorPartyId(), p.accountStatus());
    }
}
