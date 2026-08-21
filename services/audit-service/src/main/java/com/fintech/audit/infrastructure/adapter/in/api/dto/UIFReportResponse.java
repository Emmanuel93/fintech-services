package com.fintech.audit.infrastructure.adapter.in.api.dto;

import com.fintech.audit.domain.UIFReport;

import java.time.Instant;
import java.util.UUID;

public record UIFReportResponse(
        UUID reportId,
        UUID partyId,
        String reportType,
        String status,
        String triggerEventType,
        String triggerAggregateId,
        Instant createdAt,
        Instant submittedAt
) {
    public static UIFReportResponse from(UIFReport r) {
        return new UIFReportResponse(
                r.getReportId(), r.getPartyId(), r.getReportType().name(),
                r.getStatus().name(), r.getTriggerEventType(), r.getTriggerAggregateId(),
                r.getCreatedAt(), r.getSubmittedAt());
    }
}
