package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** credit-portfolio.disposition-failed → the borrower hears the money could not be deposited (D63). */
@Component
public class DispositionFailedListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionFailedListener.class);

    private final NotificationTriggerService triggerService;

    public DispositionFailedListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "credit-portfolio.disposition-failed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "dispositionFailedListenerContainerFactory")
    public void onMessage(DispositionFailedPayload p) {
        if (p.dispositionId() == null || p.obligorPartyId() == null) {
            log.warn("disposition-failed sin disposición o sin titular — ignorado");
            return;
        }
        log.debug("disposition-failed creditAccountId={} outcome={} amount={}", p.creditAccountId(), p.outcome(),
                p.amount());
        // One notice per disposition, however many times the fact arrives.
        triggerService.onDispositionFailed("DISPOSITION_FAILED:" + p.dispositionId(), p.obligorPartyId(), p.amount(),
                p.accountCancelled());
    }
}
