package com.fintech.origination.infrastructure.adapter.out.messaging;

import com.fintech.origination.application.port.out.ContractEventPublisher;
import com.fintech.origination.domain.event.ContractSignedEvent;
import com.fintech.origination.domain.event.CreditProductCreationRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaContractPublisher implements ContractEventPublisher {

    static final String TOPIC_CONTRACT_SIGNED = "origination.contract-signed";
    static final String TOPIC_CREATION_REQUESTED = "origination.credit-product-creation-requested";

    private static final Logger log = LoggerFactory.getLogger(KafkaContractPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaContractPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishContractSigned(ContractSignedEvent event) {
        kafkaTemplate.send(TOPIC_CONTRACT_SIGNED, event.getApplicationId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("ContractSignedEvent publish failed applicationId={}: {}",
                                event.getApplicationId(), ex.getMessage());
                    } else {
                        log.info("ContractSignedEvent published applicationId={}", event.getApplicationId());
                    }
                });
    }

    @Override
    public void publishCreditProductCreationRequested(CreditProductCreationRequestedEvent event) {
        kafkaTemplate.send(TOPIC_CREATION_REQUESTED, event.getApplicationId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("CreditProductCreationRequested publish failed applicationId={}: {}",
                                event.getApplicationId(), ex.getMessage());
                    } else {
                        log.info("CreditProductCreationRequested published applicationId={} contractNumber={}",
                                event.getApplicationId(), event.getContractNumber());
                    }
                });
    }
}
