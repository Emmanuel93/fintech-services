package com.fintech.scoring.infrastructure.adapter.in.api.dto;

import com.fintech.scoring.domain.RuleEvaluationDetail;
import com.fintech.scoring.domain.ScoreEvaluation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScoreEvaluationResponse(
        UUID evaluationId,
        UUID prospectId,
        UUID reportId,
        UUID policyId,
        int totalScore,
        String riskLevel,
        String decision,
        Instant evaluatedAt,
        List<RuleEvaluationDetail> ruleDetails
) {
    public static ScoreEvaluationResponse from(ScoreEvaluation e) {
        return new ScoreEvaluationResponse(
                e.getEvaluationId(), e.getProspectId(), e.getReportId(),
                e.getPolicyId(), e.getTotalScore(),
                e.getRiskLevel().name(), e.getDecision().name(),
                e.getEvaluatedAt(), e.getRuleDetails());
    }
}
