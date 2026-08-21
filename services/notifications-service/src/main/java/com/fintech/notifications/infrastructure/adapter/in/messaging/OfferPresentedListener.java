package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fintech.notifications.application.service.NotificationTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OfferPresentedListener {

    private static final Logger log = LoggerFactory.getLogger(OfferPresentedListener.class);
    private final NotificationTriggerService triggerService;

    public OfferPresentedListener(NotificationTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @KafkaListener(topics = "origination.offer-presented",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "offerPresentedListenerContainerFactory")
    public void onMessage(OfferPresentedPayload p) {
        log.debug("offer-presented applicationId={} prospectId={}", p.applicationId(), p.prospectId());
        triggerService.onOfferPresented(p.eventId(), p.applicationId(), p.prospectId(),
                p.offeredAmount(), p.offeredTerm(), p.nominalRate(), p.cat(), p.validUntil());
    }
}
