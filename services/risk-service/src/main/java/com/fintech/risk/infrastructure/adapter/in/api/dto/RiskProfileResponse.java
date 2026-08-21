package com.fintech.risk.infrastructure.adapter.in.api.dto;

import com.fintech.risk.domain.RiskProfile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RiskProfileResponse(
        UUID riskProfileId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String productType,
        int daysDelinquent,
        String bucket,
        String ifrs9Stage,
        Instant stageEnteredAt,
        boolean isForborne,
        BigDecimal ead,
        BigDecimal expectedLossRate,
        BigDecimal provisionAmount,
        String status,
        Instant lastCalculatedAt
) {
    public static RiskProfileResponse from(RiskProfile p) {
        return new RiskProfileResponse(
                p.getRiskProfileId(), p.getCreditAccountId(), p.getObligorPartyId(), p.getProductType(),
                p.getDaysDelinquent(), p.getBucket().name(), p.getIfrs9Stage().name(), p.getStageEnteredAt(),
                p.isForborne(), p.getEad(), p.getExpectedLossRate(), p.getProvisionAmount(),
                p.getStatus().name(), p.getLastCalculatedAt());
    }
}
