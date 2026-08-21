CREATE TABLE credit_product.credit_product_definitions
(
    product_definition_id    UUID          NOT NULL,
    product_code             VARCHAR(50)   NOT NULL,

    -- Business version (1, 2, 3…) — separate from optimistic-lock `version`
    product_version          INTEGER       NOT NULL DEFAULT 1,

    product_type             VARCHAR(30)   NOT NULL,
    name                     VARCHAR(100)  NOT NULL,
    description              TEXT,
    status                   VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    target_audience          VARCHAR(10)   NOT NULL,
    currency                 VARCHAR(3)    NOT NULL DEFAULT 'MXN',

    -- Tasas en decimal (ej. 0.3200 = 32% anual) — fallback cuando no hay rate_card match
    nominal_rate_annual      NUMERIC(7,4)  NOT NULL,
    moratorium_rate_annual   NUMERIC(7,4)  NOT NULL,

    -- Plazo en meses (null para REVOLVING)
    min_term                 INTEGER,
    max_term                 INTEGER,
    default_term             INTEGER,

    -- Montos (null para REVOLVING)
    min_amount               NUMERIC(19,4),
    max_amount               NUMERIC(19,4),

    -- Línea de crédito (null para INSTALLMENT)
    default_credit_line      NUMERIC(19,4),
    min_credit_line          NUMERIC(19,4),
    max_credit_line          NUMERIC(19,4),

    -- Incremento mínimo de monto/línea (null = sin restricción)
    -- INSTALLMENT: los montos deben ser múltiplos de este valor (ej. 1000 → $5k, $6k, $7k)
    -- REVOLVING: las líneas deben ser múltiplos de este valor (ej. 1000 → $10k, $11k, $12k)
    amount_step              INTEGER,

    -- Amortización (null para REVOLVING)
    amortization_type        VARCHAR(20),

    -- Frecuencia de pago por defecto (null para REVOLVING)
    default_payment_frequency VARCHAR(20),

    -- Aprobación
    min_approval_score       INTEGER       NOT NULL,
    default_approval_flow    VARCHAR(20)   NOT NULL,

    -- Fees
    opening_fee_rate         NUMERIC(7,4)  NOT NULL DEFAULT 0,
    prepayment_fee_rate      NUMERIC(7,4)  NOT NULL DEFAULT 0,

    -- Capability matrix (JSONB) — governs credit-portfolio motor behaviour
    capabilities             JSONB         NOT NULL DEFAULT '{}',

    -- Timestamps y control
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    activated_at             TIMESTAMPTZ,
    retired_at               TIMESTAMPTZ,
    deprecated_at            TIMESTAMPTZ,
    version                  BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_credit_product_definitions
        PRIMARY KEY (product_definition_id),

    -- PD-01: at most ONE active row per productCode
    -- (no global unique — same code can have multiple historical versions)

    CONSTRAINT ck_product_type
        CHECK (product_type IN (
            'PERSONAL_LOAN', 'REVOLVING_LINE', 'DISTRIBUTOR_LINE',
            'GROUP_LOAN', 'PAYROLL_LOAN', 'CREDIT_CARD', 'MICRO_LOAN',
            'SME_LOAN', 'BUSINESS_REVOLVING_LINE'
        )),
    CONSTRAINT ck_product_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'RETIRED', 'DEPRECATED')),
    CONSTRAINT ck_target_audience
        CHECK (target_audience IN ('B2C', 'B2B2C', 'B2B')),
    CONSTRAINT ck_approval_flow
        CHECK (default_approval_flow IN ('AUTOMATIC', 'MANUAL', 'COMMITTEE')),
    CONSTRAINT ck_amortization_type
        CHECK (amortization_type IS NULL OR amortization_type IN ('FRENCH', 'GERMAN', 'BULLET')),
    CONSTRAINT ck_default_payment_frequency
        CHECK (default_payment_frequency IS NULL OR default_payment_frequency IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY')),
    CONSTRAINT ck_nominal_rate
        CHECK (nominal_rate_annual > 0 AND nominal_rate_annual < 1),
    CONSTRAINT ck_moratorium_rate
        CHECK (moratorium_rate_annual > 0 AND moratorium_rate_annual < 1),
    CONSTRAINT ck_opening_fee
        CHECK (opening_fee_rate >= 0),
    CONSTRAINT ck_prepayment_fee
        CHECK (prepayment_fee_rate >= 0),
    CONSTRAINT ck_product_version
        CHECK (product_version >= 1)
);

-- PD-01: partial unique index — only one ACTIVE row per productCode
CREATE UNIQUE INDEX uq_cpd_active_product_code
    ON credit_product.credit_product_definitions (product_code)
    WHERE status = 'ACTIVE';

-- Query indexes
CREATE INDEX idx_cpd_status        ON credit_product.credit_product_definitions (status);
CREATE INDEX idx_cpd_product_type  ON credit_product.credit_product_definitions (product_type);
CREATE INDEX idx_cpd_audience      ON credit_product.credit_product_definitions (target_audience);
CREATE INDEX idx_cpd_code_version  ON credit_product.credit_product_definitions (product_code, product_version DESC);
