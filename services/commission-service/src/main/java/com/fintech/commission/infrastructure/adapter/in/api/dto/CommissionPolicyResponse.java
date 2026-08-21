package com.fintech.commission.infrastructure.adapter.in.api.dto;

import com.fintech.commission.domain.CommissionPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CommissionPolicyResponse(
        UUID policyId,
        String productType,
        UUID distributorPartyId,
        String commissionType,
        BigDecimal rate,
        int version,
        String status,
        Instant createdAt
) {
    public static CommissionPolicyResponse from(CommissionPolicy p) {
        return new CommissionPolicyResponse(p.getPolicyId(), p.getProductType(), p.getDistributorPartyId(),
                p.getCommissionType().name(), p.getRate(), p.getVersion(), p.getStatus().name(), p.getCreatedAt());
    }
}
