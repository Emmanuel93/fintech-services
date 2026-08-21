package com.fintech.scoring.infrastructure.adapter.in.api.dto;

import com.fintech.scoring.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScoringPolicyResponse(
        UUID policyId,
        String prospectType,
        String productTypeIntent,
        String name,
        String description,
        boolean active,
        int version,
        Instant createdAt,
        List<RuleResponse> rules,
        List<ThresholdResponse> thresholds
) {
    public record RuleResponse(
            UUID ruleId, String ruleType, String creditType,
            String operator, double thresholdValue,
            int scoreContribution, boolean disqualifying,
            Integer periodMonths, String description) {}

    public record ThresholdResponse(UUID thresholdId, String riskLevel,
                                    int minScore, String decision) {}

    public static ScoringPolicyResponse from(ScoringPolicy p) {
        List<RuleResponse> rules = p.getRules().stream()
                .map(r -> new RuleResponse(r.getRuleId(), r.getRuleType().name(),
                        r.getCreditType(), r.getOperator().name(),
                        r.getThresholdValue().doubleValue(),
                        r.getScoreContribution(), r.isDisqualifying(),
                        r.getPeriodMonths(), r.getDescription()))
                .toList();

        List<ThresholdResponse> thresholds = p.getThresholds().stream()
                .map(t -> new ThresholdResponse(t.getThresholdId(),
                        t.getRiskLevel().name(), t.getMinScore(), t.getDecision().name()))
                .toList();

        return new ScoringPolicyResponse(p.getPolicyId(), p.getProspectType(),
                p.getProductTypeIntent(), p.getName(), p.getDescription(),
                p.isActive(), p.getVersion(), p.getCreatedAt(), rules, thresholds);
    }
}
