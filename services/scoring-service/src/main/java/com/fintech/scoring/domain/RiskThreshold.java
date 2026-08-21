package com.fintech.scoring.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "risk_thresholds", schema = "scoring")
public class RiskThreshold {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID thresholdId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false, updatable = false)
    private ScoringPolicy policy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private RiskLevel riskLevel;

    /** Score mínimo (inclusive) para que aplique este nivel. */
    @Column(nullable = false, updatable = false)
    private int minScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ScoringDecision decision;

    protected RiskThreshold() {}

    public static RiskThreshold create(UUID thresholdId, ScoringPolicy policy,
                                       RiskLevel riskLevel, int minScore,
                                       ScoringDecision decision) {
        RiskThreshold t = new RiskThreshold();
        t.thresholdId = thresholdId;
        t.policy      = policy;
        t.riskLevel   = riskLevel;
        t.minScore    = minScore;
        t.decision    = decision;
        return t;
    }

    public UUID getThresholdId()      { return thresholdId; }
    public RiskLevel getRiskLevel()   { return riskLevel; }
    public int getMinScore()          { return minScore; }
    public ScoringDecision getDecision() { return decision; }
}
