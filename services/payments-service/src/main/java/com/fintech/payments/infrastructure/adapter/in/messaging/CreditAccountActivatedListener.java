package com.fintech.payments.infrastructure.adapter.in.messaging;

import com.fintech.payments.application.service.BalanceSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CreditAccountActivatedListener {

    private static final Logger log = LoggerFactory.getLogger(CreditAccountActivatedListener.class);

    private final BalanceSnapshotService snapshotService;

    public CreditAccountActivatedListener(BalanceSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @KafkaListener(
            topics = "credit-portfolio.credit-account-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "creditAccountActivatedListenerContainerFactory")
    public void onCreditAccountActivated(CreditAccountActivatedPayload event) {
        log.info("credit-account-activated received creditAccountId={}", event.creditAccountId());
        snapshotService.initSnapshot(event.creditAccountId(), event.obligorPartyId(), event.creditLimit());
    }
}
