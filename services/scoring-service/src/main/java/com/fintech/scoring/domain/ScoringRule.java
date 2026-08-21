package com.fintech.scoring.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "scoring_rules", schema = "scoring")
public class ScoringRule {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID ruleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false, updatable = false)
    private ScoringPolicy policy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private RuleType ruleType;

    /** Código CDC de 2 chars (TC, FM, PP…). Null = aplica a todos los créditos. */
    @Column(updatable = false, length = 10)
    private String creditType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 5)
    private RuleOperator operator;

    @Column(nullable = false, updatable = false, precision = 15, scale = 2)
    private BigDecimal thresholdValue;

    /** Puntos sumados/restados al score cuando la condición se cumple. */
    @Column(nullable = false, updatable = false)
    private int scoreContribution;

    /** Si true y la condición se cumple → ALTO inmediato sin evaluar el resto. */
    @Column(name = "is_disqualifying", nullable = false, updatable = false)
    private boolean disqualifying;

    /** Solo para INQUIRY_COUNT: ventana de tiempo en meses. */
    @Column(updatable = false)
    private Integer periodMonths;

    @Column(updatable = false, length = 200)
    private String description;

    protected ScoringRule() {}

    public static ScoringRule create(UUID ruleId, ScoringPolicy policy, RuleType ruleType,
                                     String creditType, RuleOperator operator,
                                     BigDecimal thresholdValue, int scoreContribution,
                                     boolean disqualifying, Integer periodMonths,
                                     String description) {
        ScoringRule r = new ScoringRule();
        r.ruleId           = ruleId;
        r.policy           = policy;
        r.ruleType         = ruleType;
        r.creditType       = creditType;
        r.operator         = operator;
        r.thresholdValue   = thresholdValue;
        r.scoreContribution= scoreContribution;
        r.disqualifying    = disqualifying;
        r.periodMonths     = periodMonths;
        r.description      = description;
        return r;
    }

    public UUID getRuleId()               { return ruleId; }
    public RuleType getRuleType()         { return ruleType; }
    public String getCreditType()         { return creditType; }
    public RuleOperator getOperator()     { return operator; }
    public BigDecimal getThresholdValue() { return thresholdValue; }
    public int getScoreContribution()     { return scoreContribution; }
    public boolean isDisqualifying()      { return disqualifying; }
    public Integer getPeriodMonths()      { return periodMonths; }
    public String getDescription()        { return description; }
}
