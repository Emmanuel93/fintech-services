package com.fintech.audit.infrastructure.adapter.in.api.dto;

import com.fintech.audit.domain.DocumentFileRef;

import java.time.Instant;
import java.util.UUID;

public record DocumentFileRefResponse(
        UUID docId,
        String docType,
        UUID partyId,
        String aggregateId,
        int retentionYears,
        Instant retainUntil,
        Instant createdAt
) {
    public static DocumentFileRefResponse from(DocumentFileRef r) {
        return new DocumentFileRefResponse(
                r.getDocId(), r.getDocType().name(), r.getPartyId(),
                r.getAggregateId(), r.getRetentionYears(), r.getRetainUntil(), r.getCreatedAt());
    }
}
