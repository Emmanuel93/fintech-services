package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.creditportfolio.application.port.out.CollectionAccountProjection;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** A person's collection CLABE as the SPEI connector assigned it ({@code stp.collection-account-assigned}). */
@Component
public class CollectionAccountAssignedListener {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectionAccountAssignedPayload(UUID collectionAccountId, UUID partyId, String clabe, String status) {}

    private final CollectionAccountProjection projection;

    public CollectionAccountAssignedListener(CollectionAccountProjection projection) {
        this.projection = projection;
    }

    @KafkaListener(topics = "stp.collection-account-assigned", groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "collectionAccountAssignedListenerContainerFactory")
    public void onAssigned(CollectionAccountAssignedPayload event) {
        if (event.partyId() == null || event.clabe() == null) {
            return;
        }
        projection.upsert(event.partyId(), event.collectionAccountId(), event.clabe(), event.status());
    }
}
