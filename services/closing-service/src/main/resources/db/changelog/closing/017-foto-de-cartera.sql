--liquibase formatted sql
--changeset closing:017-foto-de-cartera
--comment The cutoff seals with the portfolio's per-account day snapshot: days past due as of D, and the principal to cross-check.

-- Copied onto each cutoff unit when the run is planned (or on demand when the unit is sealed): what the
-- portfolio saw at the end of D. `days_delinquent_at_day` is what the statement prints; `principal_at_day`
-- must equal the principal closing rewinds to D, or the unit fails and the account shows up in trouble.
ALTER TABLE closing.close_units
    ADD COLUMN IF NOT EXISTS days_delinquent_at_day INTEGER,
    ADD COLUMN IF NOT EXISTS principal_at_day       NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS snapshot_taken_at      TIMESTAMPTZ;
