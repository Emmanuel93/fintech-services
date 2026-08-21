package com.fintech.risk.infrastructure.adapter.in.api.dto;

import com.fintech.risk.application.ProvisionSummary;

import java.math.BigDecimal;
import java.util.List;

public record ProvisionSummaryResponse(
        BigDecimal totalProvision,
        BigDecimal totalEad,
        long activeProfiles,
        List<Row> breakdown
) {
    public record Row(String productType, String ifrs9Stage, long count,
                      BigDecimal totalEad, BigDecimal totalProvision) {}

    public static ProvisionSummaryResponse from(ProvisionSummary s) {
        List<Row> rows = s.breakdown().stream()
                .map(r -> new Row(r.productType(), r.ifrs9Stage(), r.count(), r.totalEad(), r.totalProvision()))
                .toList();
        return new ProvisionSummaryResponse(s.totalProvision(), s.totalEad(), s.activeProfiles(), rows);
    }
}
