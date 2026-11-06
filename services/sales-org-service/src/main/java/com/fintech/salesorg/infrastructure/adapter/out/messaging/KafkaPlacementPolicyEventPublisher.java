package com.fintech.salesorg.infrastructure.adapter.out.messaging;

import com.fintech.salesorg.application.EffectivePlacementPolicy;
import com.fintech.salesorg.application.port.out.PlacementPolicyEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class KafkaPlacementPolicyEventPublisher implements PlacementPolicyEventPublisher {

    public static final String TOPIC = "sales-org.placement-policy-changed";

    private static final Logger log = LoggerFactory.getLogger(KafkaPlacementPolicyEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaPlacementPolicyEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishChanged(EffectivePlacementPolicy policy) {
        // Keyed by unit code: every change of one unit lands in order on the same partition.
        kafkaTemplate.send(TOPIC, policy.unitCode(), PlacementPolicyChangedPayload.of(policy, Instant.now()));
        log.debug("placement-policy-changed unit={} configured={} from={}", policy.unitCode(), policy.configured(), policy.inheritedFrom());
    }
}
