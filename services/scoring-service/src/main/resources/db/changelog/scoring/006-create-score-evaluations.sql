-- D2 Scoring | Score evaluation results — one per (prospect, CDC fetch).

CREATE TABLE scoring.score_evaluations (
    evaluation_id UUID        NOT NULL,
    prefetch_id   UUID        NOT NULL,
    report_id     UUID        NOT NULL,
    prospect_id   UUID        NOT NULL,
    policy_id     UUID        NOT NULL,
    total_score   INTEGER     NOT NULL,
    risk_level    VARCHAR(10) NOT NULL,
    decision      VARCHAR(20) NOT NULL,
    evaluated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    rule_details  JSONB,
    CONSTRAINT pk_score_evaluations PRIMARY KEY (evaluation_id),
    -- prefetch_id and report_id are plain UUID references (no FK) because the evaluation
    -- runs in REQUIRES_NEW — a separate transaction that cannot see the outer tx's uncommitted CDC data.
    CONSTRAINT fk_score_evaluations_policy
        FOREIGN KEY (policy_id) REFERENCES scoring.scoring_policies (policy_id),
    CONSTRAINT chk_score_evaluations_risk
        CHECK (risk_level IN ('BAJO','MEDIO','ALTO')),
    CONSTRAINT chk_score_evaluations_decision
        CHECK (decision IN ('AUTO_APPROVED','MANUAL_REVIEW','REJECTED'))
);

CREATE INDEX idx_score_evaluations_prospect
    ON scoring.score_evaluations (prospect_id, evaluated_at DESC);

COMMENT ON TABLE scoring.score_evaluations IS
    'Immutable result of evaluating a CirculoReport against a ScoringPolicy.';
COMMENT ON COLUMN scoring.score_evaluations.rule_details IS
    'JSON array of per-rule results: [{ruleId, ruleType, creditType, matched, scoreApplied, disqualifying, detail}]';
