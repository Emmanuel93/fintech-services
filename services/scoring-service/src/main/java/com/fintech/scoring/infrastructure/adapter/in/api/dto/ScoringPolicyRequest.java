package com.fintech.scoring.infrastructure.adapter.in.api.dto;

import com.fintech.scoring.domain.NoHistoryRequirement;
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
        @NotNull @NotEmpty @Valid List<RiskThresholdRequest> thresholds,
        // Opcional: sin él, quien no tiene historial recibe score 0 y nada más que pedir.
        @Valid NoHistoryRequest noHistory
) {
    /**
     * Qué hace la política con quien no tiene historial en buró.
     *
     * @param score        score que recibe (pasa por las bandas de la política); 0 si no viene
     * @param requirements códigos de {@code NoHistoryRequirement} que se le piden en revisión manual
     */
    public record NoHistoryRequest(Integer score, List<NoHistoryRequirement> requirements) {}
}
