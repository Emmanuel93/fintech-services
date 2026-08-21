package com.fintech.audit.infrastructure.adapter.in.api.dto;

import com.fintech.audit.domain.AuditEntry;

import java.time.Instant;
import java.util.UUID;

public record AuditEntryDetailResponse(
        UUID entryId,
        String category,
        String eventType,
        String action,
        String domainSource,
        String aggregateId,
        String partyId,
        String correlationId,
        String actor,
        String actorEmail,
        String actorName,
        String actorCurp,
        String actorPhone,
        String actorRoles,
        String actorChannel,
        String actorIp,
        String userAgent,
        String sessionId,
        String subjectName,
        String subjectCurp,
        String subjectEmail,
        String subjectPhone,
        String resourceType,
        String resourceLabel,
        String resourceId,
        String httpMethod,
        String httpPath,
        String httpQuery,
        String outcome,
        Integer statusCode,
        Long durationMs,
        String payload,
        Instant occurredAt,
        String occurredAtLabel,
        Instant createdAt,
        String createdAtLabel
) {
    public static AuditEntryDetailResponse from(AuditEntry e) {
        return new AuditEntryDetailResponse(
                e.getEntryId(), e.getCategory(), e.getEventType(), e.getAction(), e.getDomainSource(),
                e.getAggregateId(), e.getPartyId(), e.getCorrelationId(),
                e.getActor(), e.getActorEmail(), e.getActorName(), e.getActorCurp(), e.getActorPhone(),
                e.getActorRoles(), e.getActorChannel(),
                e.getActorIp(), e.getUserAgent(), e.getSessionId(),
                e.getSubjectName(), e.getSubjectCurp(), e.getSubjectEmail(), e.getSubjectPhone(),
                e.getResourceType(), AuditEntryResponse.resourceLabel(e.getResourceType()), e.getResourceId(),
                e.getHttpMethod(), e.getHttpPath(), e.getHttpQuery(),
                e.getOutcome(), e.getStatusCode(), e.getDurationMs(),
                e.getPayload(),
                e.getOccurredAt(), AuditEntryResponse.label(e.getOccurredAt()),
                e.getCreatedAt(), AuditEntryResponse.label(e.getCreatedAt()));
    }
}
