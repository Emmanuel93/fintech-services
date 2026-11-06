package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.service.PlacementPolicyCopyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Keeps the copy of the placement policies: one message per unit, already resolved by sales-org. */
@Component
public class PlacementPolicyChangedListener {

    private static final Logger log = LoggerFactory.getLogger(PlacementPolicyChangedListener.class);

    private final PlacementPolicyCopyService copies;

    public PlacementPolicyChangedListener(PlacementPolicyCopyService copies) {
        this.copies = copies;
    }

    @KafkaListener(topics = "sales-org.placement-policy-changed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "placementPolicyChangedListenerContainerFactory")
    public void onMessage(PlacementPolicyChangedPayload p) {
        if (p.unitCode() == null || p.unitCode().isBlank()) return;
        log.info("placement-policy-changed recibida unidad={} nivel={} configurada={}", p.unitCode(), p.levelCode(), p.configured());
        copies.upsert(p.unitCode(), p.unitId(), p.path(), p.levelCode(), p.partyRef(), p.configured(), p.inheritedFrom(),
                p.nuevo() == null ? null : p.nuevo().toDomain(), p.revolvente() == null ? null : p.revolvente().toDomain(),
                p.eventId(), p.occurredOn());
    }
}
