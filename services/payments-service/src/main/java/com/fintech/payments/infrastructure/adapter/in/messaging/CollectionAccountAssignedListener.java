package com.fintech.payments.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.payments.application.port.out.CollectionAccountDirectory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** A payer's collection CLABE as the connector assigned it ({@code stp.collection-account-assigned}). */
@Component
public class CollectionAccountAssignedListener {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectionAccountAssignedPayload(UUID collectionAccountId, UUID partyId, String clabe, String status) {}

    private final CollectionAccountDirectory directory;

    public CollectionAccountAssignedListener(CollectionAccountDirectory directory) {
        this.directory = directory;
    }

    @KafkaListener(topics = "stp.collection-account-assigned", groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "collectionAccountAssignedListenerContainerFactory")
    public void onAssigned(CollectionAccountAssignedPayload event) {
        if (event.partyId() == null || event.clabe() == null) {
            return;
        }
        directory.record(event.partyId(), event.collectionAccountId(), event.clabe(), event.status());
    }
}
