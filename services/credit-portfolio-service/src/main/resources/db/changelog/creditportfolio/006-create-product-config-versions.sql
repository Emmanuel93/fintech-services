-- ─────────────────────────────────────────────────────────────────────────────
-- Local read-model of credit-product config versions (ADR-001 read models locales).
--
-- Built by projecting product-catalog.product-activated / product-retired events.
-- A CreditAccount pins (product_code, product_version) and reads its behaviour config
-- from here for its lifetime. Versions are immutable; retiring only flips status.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE credit_portfolio.product_config_versions
(
    id                      UUID          NOT NULL,
    product_code            VARCHAR(50)   NOT NULL,
    product_version         INTEGER       NOT NULL,
    product_type            VARCHAR(30)   NOT NULL,
    behavior                VARCHAR(20)   NOT NULL,
    target_audience         VARCHAR(10),

    -- Capability matrix that drives the engine (JSONB)
    capabilities            JSONB         NOT NULL DEFAULT '{}',

    amortization_type       VARCHAR(20),                 -- FRENCH | GERMAN | BULLET (null revolving)
    payment_frequency       VARCHAR(20),                 -- WEEKLY | BIWEEKLY | MONTHLY
    amount_step             INTEGER,

    nominal_rate_annual     NUMERIC(7,4),
    moratorium_rate_annual  NUMERIC(7,4),
    opening_fee_rate        NUMERIC(7,4),

    status                  VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    degraded                BOOLEAN       NOT NULL DEFAULT FALSE,

    received_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_product_config_versions PRIMARY KEY (id),
    CONSTRAINT uq_pcv_code_version UNIQUE (product_code, product_version),
    CONSTRAINT ck_pcv_behavior CHECK (behavior IN ('INSTALLMENT', 'REVOLVING')),
    CONSTRAINT ck_pcv_status   CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_pcv_amortization
        CHECK (amortization_type IS NULL OR amortization_type IN ('FRENCH', 'GERMAN', 'BULLET')),
    CONSTRAINT ck_pcv_frequency
        CHECK (payment_frequency IS NULL OR payment_frequency IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY'))
);

CREATE INDEX idx_pcv_product_code ON credit_portfolio.product_config_versions (product_code);
CREATE INDEX idx_pcv_status       ON credit_portfolio.product_config_versions (status);
