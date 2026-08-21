package com.fintech.creditproduct.infrastructure.adapter.in.api.dto;

import com.fintech.creditproduct.domain.EligibilityRule;

import java.math.BigDecimal;
import java.util.UUID;

public record EligibilityRuleResponse(
        UUID ruleId,
        String ruleType,
        String operator,
        BigDecimal thresholdValue,
        String stringValue,
        String errorCode
) {
    public static EligibilityRuleResponse from(EligibilityRule rule) {
        return new EligibilityRuleResponse(
                rule.getRuleId(),
                rule.getRuleType().name(),
                rule.getOperator() != null ? rule.getOperator().name() : null,
                rule.getThresholdValue(),
                rule.getStringValue(),
                rule.getErrorCode());
    }
}
