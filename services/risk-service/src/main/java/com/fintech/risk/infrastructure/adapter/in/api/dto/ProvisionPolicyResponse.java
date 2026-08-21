package com.fintech.risk.infrastructure.adapter.in.api.dto;

import com.fintech.risk.domain.DelinquencyBucket;
import com.fintech.risk.domain.ProvisionPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ProvisionPolicyResponse(
        UUID policyId,
        String productType,
        int version,
        String status,
        Map<String, BigDecimal> rates,
        Instant createdAt
) {
    public static ProvisionPolicyResponse from(ProvisionPolicy p) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (DelinquencyBucket b : DelinquencyBucket.values()) {
            p.rateMatrix().computeIfPresent(b, (k, v) -> { rates.put(k.name(), v); return v; });
        }
        return new ProvisionPolicyResponse(
                p.getPolicyId(), p.getProductType(), p.getVersion(), p.getStatus().name(), rates, p.getCreatedAt());
    }
}
