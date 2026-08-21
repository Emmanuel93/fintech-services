package com.fintech.party.infrastructure.adapter.in.api.dto;

import com.fintech.party.domain.PartyRelationship;

import java.time.Instant;
import java.util.UUID;

public record PartyRelationshipResponse(
        UUID relationshipId,
        UUID partyId,
        UUID relatedPartyId,
        String relationshipType,
        UUID creditProductId,
        boolean active,
        Instant createdAt,
        Instant endedAt) {

    public static PartyRelationshipResponse from(PartyRelationship r) {
        return new PartyRelationshipResponse(
                r.getRelationshipId(), r.getPartyId(), r.getRelatedPartyId(),
                r.getRelationshipType(), r.getCreditProductId(),
                r.isActive(), r.getCreatedAt(), r.getEndedAt());
    }
}
