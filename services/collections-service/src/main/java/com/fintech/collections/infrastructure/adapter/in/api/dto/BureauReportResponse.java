package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.BureauReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BureauReportResponse(
        UUID reportId,
        UUID creditAccountId,
        String eventType,
        BigDecimal amountReported,
        String status,
        String bureauReference,
        Instant createdAt
) {
    public static BureauReportResponse from(BureauReport r) {
        return new BureauReportResponse(r.getReportId(), r.getCreditAccountId(), r.getEventType().name(),
                r.getAmountReported(), r.getStatus().name(), r.getBureauReference(), r.getCreatedAt());
    }
}
