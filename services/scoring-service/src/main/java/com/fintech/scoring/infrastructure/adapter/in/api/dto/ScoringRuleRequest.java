package com.fintech.scoring.infrastructure.adapter.in.api.dto;

import com.fintech.scoring.domain.RuleOperator;
import com.fintech.scoring.domain.RuleType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ScoringRuleRequest(
        @NotNull RuleType    ruleType,
        String               creditType,          // null = aplica a todos; código CDC 2 chars
        @NotNull RuleOperator operator,
        @NotNull BigDecimal  thresholdValue,
        int                  scoreContribution,
        boolean              disqualifying,
        Integer              periodMonths,         // solo para INQUIRY_COUNT
        String               description
) {}
