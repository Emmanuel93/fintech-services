package com.fintech.channels.infrastructure.adapter.in.api.dto;

import com.fintech.channels.domain.LeadRequest;

import java.time.Instant;
import java.util.UUID;

public record LeadResponse(
        UUID leadId,
        UUID channelId,
        String intentType,
        String status,
        String firstName,
        String lastName1,
        String phone,
        String email,
        UUID promoterPartyId,
        UUID convertedPartyId,
        Instant expiresAt,
        Instant createdAt
) {
    public static LeadResponse from(LeadRequest lr) {
        return new LeadResponse(
                lr.getLeadId(), lr.getChannelId(), lr.getIntentType(), lr.getStatus(),
                lr.getFirstName(), lr.getLastName1(), lr.getPhone(), lr.getEmail(),
                lr.getPromoterPartyId(), lr.getConvertedPartyId(),
                lr.getExpiresAt(), lr.getCreatedAt());
    }
}
