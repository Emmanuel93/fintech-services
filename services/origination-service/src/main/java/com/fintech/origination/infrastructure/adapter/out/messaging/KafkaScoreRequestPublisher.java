package com.fintech.origination.infrastructure.adapter.out.messaging;

import com.fintech.origination.application.port.out.ScoreRequestPublisher;
import com.fintech.origination.domain.event.ScoreRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Asks scoring for a decision once the application is committed, not before.
 *
 * <p>scoring answers with scoring-completed carrying the application id, and origination applies the decision by
 * looking the application up. Sent inside the transaction, a fast answer could arrive before the application was
 * committed: the lookup found nothing, the decision was logged as «ignored» and the application waited for good.
 * Same race payments had with its orders (3 Nov). The send leaves only if the transaction committed.
 */
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
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(event);
                }
            });
            return;
        }
        send(event);
    }

    private void send(ScoreRequestedEvent event) {
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
