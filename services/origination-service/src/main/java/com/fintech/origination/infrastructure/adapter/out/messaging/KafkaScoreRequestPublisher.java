package com.fintech.origination.infrastructure.adapter.out.messaging;

import com.fintech.origination.application.port.out.ScoreRequestPublisher;
import com.fintech.origination.domain.event.ScoreRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaScoreRequestPublisher implements ScoreRequestPublisher {

    static final String TOPIC = "origination.score-requested";

    private static final Logger log = LoggerFactory.getLogger(KafkaScoreRequestPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaScoreRequestPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(ScoreRequestedEvent event) {
        kafkaTemplate.send(TOPIC, event.getProspectId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("ScoreRequestedEvent publish failed topic={} applicationId={} prospectId={}: {}",
                                TOPIC, event.getApplicationId(), event.getProspectId(), ex.getMessage());
                    } else {
                        log.info("ScoreRequestedEvent published topic={} applicationId={} prospectId={} partition={} offset={}",
                                TOPIC, event.getApplicationId(), event.getProspectId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
