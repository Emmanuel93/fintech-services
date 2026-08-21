package com.fintech.scoring.infrastructure.adapter.in.messaging;

import com.fintech.scoring.application.service.BureauPrefetchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class ProspectCreatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProspectCreatedEventListener.class);

    private final BureauPrefetchService prefetchService;

    public ProspectCreatedEventListener(BureauPrefetchService prefetchService) {
        this.prefetchService = prefetchService;
    }

    @KafkaListener(
            topics = "origination.prospect-created",
            groupId = "scoring-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onProspectCreated(@Payload ProspectCreatedPayload payload) {
        log.info("Received ProspectCreatedEvent prospectId={} circuloConsent={}",
                payload.prospectId(), payload.circuloConsentAccepted());

        if (!payload.circuloConsentAccepted()) {
            log.info("Skipping Círculo prefetch — consent not granted prospectId={}", payload.prospectId());
            return;
        }

        prefetchService.initiate(
                payload.prospectId(),
                payload.curp(),
                payload.eventId() != null ? payload.eventId() : payload.prospectId().toString(),
                payload.firstName(),
                payload.lastName1(),
                payload.lastName2(),
                payload.rfc(),
                payload.dateOfBirth(),
                payload.street(),
                payload.exteriorNumber(),
                payload.interiorNumber(),
                payload.neighborhood(),
                payload.municipality(),
                payload.city(),
                payload.state(),
                payload.postalCode(),
                payload.prospectType(),
                payload.productTypeIntent());
    }
}
