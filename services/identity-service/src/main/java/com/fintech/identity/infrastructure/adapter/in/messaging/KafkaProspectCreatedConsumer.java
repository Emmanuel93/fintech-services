package com.fintech.identity.infrastructure.adapter.in.messaging;

import com.fintech.identity.application.port.in.ProvisionProspectCredentialUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaProspectCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaProspectCreatedConsumer.class);

    private final ProvisionProspectCredentialUseCase provisionUseCase;

    public KafkaProspectCreatedConsumer(ProvisionProspectCredentialUseCase provisionUseCase) {
        this.provisionUseCase = provisionUseCase;
    }

    @KafkaListener(
            topics = "origination.prospect-created",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "prospectListenerContainerFactory")
    public void onProspectCreated(ProspectCreatedMessage message) {
        log.info("Kafka[origination.prospect-created] received prospectId={} username={}",
                message.prospectId(), message.username());
        provisionUseCase.provisionFromProspect(
                message.prospectId(),
                message.username(),
                message.password());
        log.info("Credential provisioning complete prospectId={} username={}",
                message.prospectId(), message.username());
    }
}
