package com.fintech.creditproduct.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Eligibility constraint sent in CreateCreditProductRequest.
 *
 * <p>For REQUIRED_PARTY_TYPE: set ruleType="REQUIRED_PARTY_TYPE", stringValue="DISTRIBUTOR" (or other).
 * For numeric rules: set ruleType, operator and thresholdValue.
 */
public record EligibilityRuleRequest(

        @NotBlank String ruleType,   // EligibilityRuleType enum name

        String operator,             // EligibilityOperator enum name — null for REQUIRED_PARTY_TYPE

        BigDecimal thresholdValue,   // numeric threshold — null for REQUIRED_PARTY_TYPE

        String stringValue,          // only for REQUIRED_PARTY_TYPE

        @NotBlank String errorCode
) {}
