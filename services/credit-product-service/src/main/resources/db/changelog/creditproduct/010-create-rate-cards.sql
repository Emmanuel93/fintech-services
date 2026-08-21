-- ─────────────────────────────────────────────────────────────────────────────
-- Migration 010: Tiered rate cards
--
-- A rate card row matches when ALL non-null band fields contain the request value.
-- Resolution: most-specific match wins (COUNT of non-null bands).
-- Fallback: product definition flat nominalRateAnnual / moratoriumRateAnnual.
--
-- Usage examples:
--   B2C PERSONAL_LOAN by risk tier:
--     (tier=T1, nominalRate=0.28) | (tier=T2, nominalRate=0.32) | (tier=T3, nominalRate=0.38)
--   B2B2C DISTRIBUTOR_LINE by amount band:
--     (minAmount=100k, maxAmount=500k, nominalRate=0.20) | (minAmount=500k, nominalRate=0.16)
--   B2B SME_LOAN by term band:
--     (minTerm=1, maxTerm=12, nominalRate=0.22) | (minTerm=13, maxTerm=36, nominalRate=0.26)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS credit_product.rate_cards
(
    rate_card_id           UUID           NOT NULL,
    product_definition_id  UUID           NOT NULL,

    -- All band fields are nullable — null = match any value
    tier_band              VARCHAR(10),
    min_amount             NUMERIC(19, 4),
    max_amount             NUMERIC(19, 4),
    min_term               INTEGER,
    max_term               INTEGER,

    nominal_rate           NUMERIC(7, 4)  NOT NULL,
    moratorium_rate        NUMERIC(7, 4)  NOT NULL,

    CONSTRAINT pk_rate_cards
        PRIMARY KEY (rate_card_id),
    CONSTRAINT fk_rc_definition
        FOREIGN KEY (product_definition_id)
        REFERENCES credit_product.credit_product_definitions (product_definition_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_rc_nominal_rate
        CHECK (nominal_rate > 0 AND nominal_rate < 1),
    CONSTRAINT ck_rc_moratorium_rate
        CHECK (moratorium_rate > 0 AND moratorium_rate < 1),
    CONSTRAINT ck_rc_amount_band
        CHECK (min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount),
    CONSTRAINT ck_rc_term_band
        CHECK (min_term IS NULL OR max_term IS NULL OR min_term <= max_term)
);

CREATE INDEX IF NOT EXISTS idx_rc_product_def
    ON credit_product.rate_cards (product_definition_id);
