package com.fintech.scoring.infrastructure.adapter.in.api.dto;

import com.fintech.scoring.domain.RiskLevel;
import com.fintech.scoring.domain.ScoringDecision;
import jakarta.validation.constraints.NotNull;

public record RiskThresholdRequest(
        @NotNull RiskLevel      riskLevel,
        int                     minScore,
        @NotNull ScoringDecision decision
) {}
