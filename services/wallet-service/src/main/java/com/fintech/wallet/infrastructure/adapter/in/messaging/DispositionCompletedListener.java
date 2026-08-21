package com.fintech.wallet.infrastructure.adapter.in.messaging;

import com.fintech.wallet.application.service.WalletProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DispositionCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionCompletedListener.class);

    private final WalletProjectionService projectionService;

    public DispositionCompletedListener(WalletProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @KafkaListener(
            topics = "credit-portfolio.disposition-completed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "dispositionCompletedListenerContainerFactory")
    public void onDispositionCompleted(DispositionCompletedPayload event) {
        log.info("disposition-completed received creditAccountId={} type={} amount={}",
                event.creditAccountId(), event.dispositionType(), event.amount());

        projectionService.onDispositionCompleted(
                event.creditAccountId(), event.amount(), event.dispositionType());
    }
}
