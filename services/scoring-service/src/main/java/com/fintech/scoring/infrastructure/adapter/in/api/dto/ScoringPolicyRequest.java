package com.fintech.scoring.infrastructure.adapter.in.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ScoringPolicyRequest(
        @NotBlank @Size(max = 20) String prospectType,
        @NotBlank @Size(max = 30) String productTypeIntent,
        @NotBlank @Size(max = 100) String name,
        String description,
        @NotNull @NotEmpty @Valid List<ScoringRuleRequest> rules,
        @NotNull @NotEmpty @Valid List<RiskThresholdRequest> thresholds
) {}
