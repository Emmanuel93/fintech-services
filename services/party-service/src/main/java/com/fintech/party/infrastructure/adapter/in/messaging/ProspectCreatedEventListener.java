package com.fintech.party.infrastructure.adapter.in.messaging;

import com.fintech.party.application.service.PartyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ProspectCreatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProspectCreatedEventListener.class);

    private final PartyService partyService;

    public ProspectCreatedEventListener(PartyService partyService) {
        this.partyService = partyService;
    }

    @KafkaListener(
            topics           = "origination.prospect-created",
            groupId          = "party-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onProspectCreated(ProspectCreatedPayload payload) {
        log.info("ProspectCreated received prospectId={} type={}",
                payload.prospectId(), payload.prospectType());
        partyService.createFromProspect(payload);
    }
}
