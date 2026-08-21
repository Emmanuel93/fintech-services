-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 011: Per-product eligibility rules
--
-- Rules evaluated by origination before creating a CreditApplication.
-- Complement the global scoring policy — these are product-specific hard limits.
--
-- Rule types:
--   MIN_AGE / MAX_AGE               → threshold_value in years
--   MIN_SCORE                       → threshold_value (bureau score floor per product)
--   MAX_EXISTING_ACTIVE_CREDITS     → threshold_value (count)
--   MIN_MONTHLY_INCOME              → threshold_value (MXN)
--   MAX_DEBT_TO_INCOME_RATIO        → threshold_value (decimal e.g. 0.40)
--   REQUIRED_PARTY_TYPE             → string_value e.g. 'DISTRIBUTOR'
--   MIN_SENIORITY_MONTHS            → threshold_value (months of employment)
--   MIN_GROUP_MEMBERS               → threshold_value (count)
--   MAX_GROUP_MEMBERS               → threshold_value (count)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS credit_product.eligibility_rules
(
    rule_id                UUID          NOT NULL,
    product_definition_id  UUID          NOT NULL,

    rule_type              VARCHAR(40)   NOT NULL,
    operator               VARCHAR(5),
    threshold_value        NUMERIC(19, 4),
    string_value           VARCHAR(50),
    error_code             VARCHAR(100)  NOT NULL,

    CONSTRAINT pk_eligibility_rules
        PRIMARY KEY (rule_id),
    CONSTRAINT fk_er_definition
        FOREIGN KEY (product_definition_id)
        REFERENCES credit_product.credit_product_definitions (product_definition_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_er_rule_type
        CHECK (rule_type IN (
            'MIN_AGE', 'MAX_AGE', 'MIN_SCORE', 'MAX_EXISTING_ACTIVE_CREDITS',
            'MIN_MONTHLY_INCOME', 'MAX_DEBT_TO_INCOME_RATIO', 'REQUIRED_PARTY_TYPE',
            'MIN_SENIORITY_MONTHS', 'MIN_GROUP_MEMBERS', 'MAX_GROUP_MEMBERS'
        )),
    CONSTRAINT ck_er_operator
        CHECK (operator IS NULL OR operator IN ('EQ', 'GT', 'GTE', 'LT', 'LTE')),
    CONSTRAINT ck_er_values
        CHECK (
            (rule_type = 'REQUIRED_PARTY_TYPE' AND string_value IS NOT NULL AND threshold_value IS NULL)
            OR
            (rule_type != 'REQUIRED_PARTY_TYPE' AND threshold_value IS NOT NULL AND operator IS NOT NULL)
        )
);

CREATE INDEX IF NOT EXISTS idx_er_product_def
    ON credit_product.eligibility_rules (product_definition_id);
