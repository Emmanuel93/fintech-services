-- D2 Scoring | Adds prospect classification columns to bureau_prefetches.
-- prospect_type and product_type_intent are needed so the future scoring engine
-- knows which model to apply when it reads the prefetched data.

ALTER TABLE scoring.bureau_prefetches
    ADD COLUMN IF NOT EXISTS prospect_type       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS product_type_intent VARCHAR(30);

COMMENT ON COLUMN scoring.bureau_prefetches.prospect_type
    IS 'INDIVIDUAL | BUSINESS — determines the scoring model family to apply';

COMMENT ON COLUMN scoring.bureau_prefetches.product_type_intent
    IS 'Product requested (PERSONAL_LOAN, REVOLVING_LINE, …) — selects the specific scoring model';
