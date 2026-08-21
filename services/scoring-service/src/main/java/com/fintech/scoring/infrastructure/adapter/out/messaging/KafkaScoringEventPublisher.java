package com.fintech.scoring.infrastructure.adapter.out.messaging;

import com.fintech.scoring.application.port.out.ScoringEventPublisher;
import com.fintech.scoring.domain.event.ScoringCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaScoringEventPublisher implements ScoringEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaScoringEventPublisher.class);
    static final String TOPIC = "scoring.scoring-completed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaScoringEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishCompleted(ScoringCompletedEvent event) {
        kafkaTemplate.send(TOPIC, event.prospectId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish ScoringCompleted prospectId={} evaluationId={} decision={}",
                                event.prospectId(), event.evaluationId(), event.decision(), ex);
                    } else {
                        log.info("ScoringCompleted published prospectId={} evaluationId={} decision={} offset={}",
                                event.prospectId(), event.evaluationId(), event.decision(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
