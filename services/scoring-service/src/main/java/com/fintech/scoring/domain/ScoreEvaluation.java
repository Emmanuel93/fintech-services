package com.fintech.scoring.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "score_evaluations", schema = "scoring")
public class ScoreEvaluation {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(nullable = false, updatable = false)
    private UUID prefetchId;

    @Column(nullable = false, updatable = false)
    private UUID reportId;

    @Column(nullable = false, updatable = false)
    private UUID prospectId;

    @Column(nullable = false, updatable = false)
    private UUID policyId;

    @Column(nullable = false, updatable = false)
    private int totalScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private RiskLevel riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ScoringDecision decision;

    @Column(nullable = false, updatable = false)
    private Instant evaluatedAt;

    /** Detalle por regla — mapeado a JSONB nativo por Hibernate (SqlTypes.JSON). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private List<RuleEvaluationDetail> ruleDetails;

    protected ScoreEvaluation() {}

    public static ScoreEvaluation create(UUID evaluationId, UUID prefetchId, UUID reportId,
                                         UUID prospectId, UUID policyId,
                                         int totalScore, RiskLevel riskLevel,
                                         ScoringDecision decision,
                                         List<RuleEvaluationDetail> ruleDetails) {
        ScoreEvaluation e = new ScoreEvaluation();
        e.evaluationId = evaluationId;
        e.prefetchId   = prefetchId;
        e.reportId     = reportId;
        e.prospectId   = prospectId;
        e.policyId     = policyId;
        e.totalScore   = totalScore;
        e.riskLevel    = riskLevel;
        e.decision     = decision;
        e.evaluatedAt  = Instant.now();
        e.ruleDetails  = List.copyOf(ruleDetails);
        return e;
    }

    public UUID getEvaluationId()               { return evaluationId; }
    public UUID getPrefetchId()                 { return prefetchId; }
    public UUID getReportId()                   { return reportId; }
    public UUID getProspectId()                 { return prospectId; }
    public UUID getPolicyId()                   { return policyId; }
    public int getTotalScore()                  { return totalScore; }
    public RiskLevel getRiskLevel()             { return riskLevel; }
    public ScoringDecision getDecision()        { return decision; }
    public Instant getEvaluatedAt()             { return evaluatedAt; }
    public List<RuleEvaluationDetail> getRuleDetails() { return ruleDetails; }
}
