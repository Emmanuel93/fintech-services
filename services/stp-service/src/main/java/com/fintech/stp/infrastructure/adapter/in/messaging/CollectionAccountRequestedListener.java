package com.fintech.stp.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.stp.application.port.in.IssueCollectionAccountUseCase;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The connector's command to give a holder a collection CLABE ({@code fintech.stp.collection.request-topic}). Whoever
 * decides that someone has to be able to pay by SPEI sends it; the connector does not know why, and answers on
 * {@code stp.collection-account-assigned}. Idempotent: a holder who has a CLABE keeps it.
 */
@Component
public class CollectionAccountRequestedListener {

    /** {@code holderId} is opaque to the connector: the requester's id of the person who will pay to the CLABE. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectionAccountRequested(String requestId, UUID holderId, String holderName, String holderTaxId) {}

    private final IssueCollectionAccountUseCase issue;

    public CollectionAccountRequestedListener(IssueCollectionAccountUseCase issue) {
        this.issue = issue;
    }

    @KafkaListener(topics = "${fintech.stp.collection.request-topic:stp.collection-account-requested}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "collectionAccountRequestedListenerContainerFactory")
    public void onRequested(CollectionAccountRequested request) {
        if (request.holderId() == null) {
            return;
        }
        issue.issueFor(request.holderId(), request.holderName(), request.holderTaxId());
    }
}
