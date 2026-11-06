package com.fintech.stp.infrastructure.adapter.in.api.dto;

import com.fintech.stp.domain.collection.CollectionAccount;

import java.time.Instant;
import java.util.UUID;

public record CollectionAccountResponse(UUID collectionAccountId, UUID partyId, String clabe, String status,
                                        String statusReason, Instant createdAt, Instant updatedAt) {
    public static CollectionAccountResponse from(CollectionAccount a) {
        return new CollectionAccountResponse(a.getCollectionAccountId(), a.getPartyId(), a.getClabe(),
                a.status().name(), a.getStatusReason(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
