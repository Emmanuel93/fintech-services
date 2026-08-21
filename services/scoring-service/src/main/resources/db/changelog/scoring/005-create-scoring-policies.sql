-- D2 Scoring | Scoring engine — configurable policies, rules and risk thresholds.

CREATE TABLE scoring.scoring_policies (
    policy_id           UUID        NOT NULL,
    prospect_type       VARCHAR(20) NOT NULL,
    product_type_intent VARCHAR(30) NOT NULL,
    name                VARCHAR(100) NOT NULL,
    description         TEXT,
    active              BOOLEAN     NOT NULL DEFAULT TRUE,
    version             INTEGER     NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_scoring_policies PRIMARY KEY (policy_id),
    CONSTRAINT chk_scoring_policies_prospect_type
        CHECK (prospect_type IN ('INDIVIDUAL', 'BUSINESS', 'DISTRIBUTOR', 'GUARANTOR'))
);

-- Only one active policy per (prospect_type, product_type_intent)
CREATE UNIQUE INDEX idx_scoring_policies_active_unique
    ON scoring.scoring_policies (prospect_type, product_type_intent)
    WHERE active = TRUE;

CREATE INDEX idx_scoring_policies_active ON scoring.scoring_policies (active);

COMMENT ON TABLE scoring.scoring_policies IS
    'Configurable scoring policies. One active policy per (prospect_type, product_type_intent).';

-- ─── Rules ────────────────────────────────────────────────────────────────────

CREATE TABLE scoring.scoring_rules (
    rule_id          UUID        NOT NULL,
    policy_id        UUID        NOT NULL,
    rule_type        VARCHAR(30) NOT NULL,
    credit_type      VARCHAR(10),           -- null = aplica a todos los créditos
    operator         VARCHAR(5)  NOT NULL,
    threshold_value  NUMERIC(15,2) NOT NULL,
    score_contribution INTEGER   NOT NULL,
    is_disqualifying BOOLEAN     NOT NULL DEFAULT FALSE,
    period_months    INTEGER,               -- ventana en meses (INQUIRY_COUNT)
    description      VARCHAR(200),
    CONSTRAINT pk_scoring_rules PRIMARY KEY (rule_id),
    CONSTRAINT fk_scoring_rules_policy
        FOREIGN KEY (policy_id) REFERENCES scoring.scoring_policies (policy_id) ON DELETE CASCADE,
    -- Las variables que el motor sabe evaluar. Eran cinco y sólo miraban lo obvio —peor atraso,
    -- FICO, número de créditos— mientras el reporte de Círculo traía treinta campos por crédito
    -- sin leer. El más caro de ignorar: saldo_vencido_peor_atraso, el importe que el cliente
    -- llegó a deber en su peor momento; sin él, atrasarse con dos mil pesos y con doscientos mil
    -- daban exactamente el mismo score.
    CONSTRAINT chk_scoring_rules_type
        CHECK (rule_type IN (
            -- Comportamiento de pago
            'MORA_CHECK','WORST_ARREARS_BALANCE','BALANCE_CHECK','OVERDUE_ACCOUNTS_COUNT',
            'CURRENT_ACCOUNTS_COUNT','OVERDUE_PAYMENTS_COUNT','ARREARS_RECENCY_MONTHS',
            'PREVENTION_KEY_COUNT',
            -- Exposición y capacidad de pago
            'TOTAL_DEBT','CREDIT_UTILIZATION','MONTHLY_PAYMENT_LOAD','DEBT_TO_INCOME',
            -- Perfil crediticio
            'FICO_THRESHOLD','CREDIT_COUNT','CREDIT_HISTORY_MONTHS','INQUIRY_COUNT',
            -- Perfil de la persona
            'AGE_YEARS','MONTHLY_INCOME','EMPLOYMENT_MONTHS','DEPENDENTS_COUNT')),
    CONSTRAINT chk_scoring_rules_operator
        CHECK (operator IN ('GT','GTE','LT','LTE','EQ'))
);

CREATE INDEX idx_scoring_rules_policy ON scoring.scoring_rules (policy_id);

COMMENT ON TABLE scoring.scoring_rules IS
    'Individual rules belonging to a scoring policy. Evaluated sequentially.';
COMMENT ON COLUMN scoring.scoring_rules.credit_type IS
    'CDC 2-char credit type code (TC=tarjeta, FM=financiera, PP=personal, HI=hipotecario…). NULL = all.';
COMMENT ON COLUMN scoring.scoring_rules.is_disqualifying IS
    'If TRUE and condition matches → immediate ALTO/REJECTED regardless of total score.';
COMMENT ON COLUMN scoring.scoring_rules.period_months IS
    'Lookback window in months. Only meaningful for INQUIRY_COUNT.';

-- ─── Risk Thresholds ─────────────────────────────────────────────────────────

CREATE TABLE scoring.risk_thresholds (
    threshold_id UUID        NOT NULL,
    policy_id    UUID        NOT NULL,
    risk_level   VARCHAR(10) NOT NULL,
    min_score    INTEGER     NOT NULL,
    decision     VARCHAR(20) NOT NULL,
    CONSTRAINT pk_risk_thresholds PRIMARY KEY (threshold_id),
    CONSTRAINT fk_risk_thresholds_policy
        FOREIGN KEY (policy_id) REFERENCES scoring.scoring_policies (policy_id) ON DELETE CASCADE,
    CONSTRAINT chk_risk_thresholds_level
        CHECK (risk_level IN ('BAJO','MEDIO','ALTO')),
    CONSTRAINT chk_risk_thresholds_decision
        CHECK (decision IN ('AUTO_APPROVED','MANUAL_REVIEW','REJECTED'))
);

CREATE INDEX idx_risk_thresholds_policy ON scoring.risk_thresholds (policy_id, min_score DESC);

COMMENT ON TABLE scoring.risk_thresholds IS
    'Maps total score ranges to risk levels (BAJO/MEDIO/ALTO) and decisions.';
COMMENT ON COLUMN scoring.risk_thresholds.min_score IS
    'Score >= min_score for this level to apply. Evaluate highest first.';
