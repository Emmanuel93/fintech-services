package com.fintech.origination.infrastructure.adapter.out.messaging;

import com.fintech.origination.application.port.out.OfferEventPublisher;
import com.fintech.origination.domain.event.OfferPresentedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOfferPublisher implements OfferEventPublisher {

    static final String TOPIC = "origination.offer-presented";
    private static final Logger log = LoggerFactory.getLogger(KafkaOfferPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaOfferPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(OfferPresentedEvent event) {
        kafkaTemplate.send(TOPIC, event.getApplicationId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("OfferPresentedEvent publish failed topic={} applicationId={}: {}",
                                TOPIC, event.getApplicationId(), ex.getMessage());
                    } else {
                        log.info("OfferPresentedEvent published applicationId={}", event.getApplicationId());
                    }
                });
    }
}
