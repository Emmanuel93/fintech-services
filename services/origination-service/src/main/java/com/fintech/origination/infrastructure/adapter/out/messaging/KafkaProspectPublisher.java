package com.fintech.origination.infrastructure.adapter.out.messaging;

import com.fintech.origination.application.port.out.ProspectEventPublisher;
import com.fintech.origination.domain.event.ProspectCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaProspectPublisher implements ProspectEventPublisher {

    static final String TOPIC = "origination.prospect-created";

    private static final Logger log = LoggerFactory.getLogger(KafkaProspectPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaProspectPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(ProspectCreatedEvent event) {
        kafkaTemplate.send(TOPIC, event.getProspectId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("ProspectCreatedEvent publish failed topic={} prospectId={} curp={}: {}",
                                TOPIC, event.getProspectId(), event.getCurp(), ex.getMessage());
                    } else {
                        log.info("ProspectCreatedEvent published topic={} prospectId={} partition={} offset={}",
                                TOPIC, event.getProspectId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
