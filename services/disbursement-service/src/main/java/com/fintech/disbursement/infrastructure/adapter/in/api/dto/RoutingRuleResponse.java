package com.fintech.disbursement.infrastructure.adapter.in.api.dto;

import com.fintech.disbursement.domain.RoutingRule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RoutingRuleResponse(
        UUID routingRuleId,
        UUID companyId,
        String rail,
        String provider,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        int priority,
        boolean enabled,
        Instant createdAt
) {

    public static RoutingRuleResponse from(RoutingRule rule) {
        return new RoutingRuleResponse(rule.getRoutingRuleId(), rule.getCompanyId(), rule.getRail(),
                rule.getProvider(), rule.getMinAmount(), rule.getMaxAmount(), rule.getPriority(),
                rule.isEnabled(), rule.getCreatedAt());
    }
}
