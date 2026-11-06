--liquibase formatted sql
--changeset closing:018-ciclo-de-facturacion
--comment Each account carries the billing cycle of the product it was opened with; the policy table stays as the fallback.

-- The cycle the account was activated with: cutoff rule, cutoff day, days to pay, grace and minimum
-- payment. It comes from the product through the activation event. Accounts opened before the product
-- declared it keep NULLs here and keep deriving from closing.close_cycle_policies.
ALTER TABLE closing.account_close_profiles
    ADD COLUMN IF NOT EXISTS billing_cutoff_rule       VARCHAR(30),
    ADD COLUMN IF NOT EXISTS billing_cutoff_day        SMALLINT,
    ADD COLUMN IF NOT EXISTS billing_due_offset_days   SMALLINT,
    ADD COLUMN IF NOT EXISTS billing_grace_days        SMALLINT,
    ADD COLUMN IF NOT EXISTS billing_min_payment_rate  NUMERIC(6, 4),
    ADD COLUMN IF NOT EXISTS billing_shift             VARCHAR(10);

-- A weekly product can cut on a fixed weekday.
ALTER TABLE closing.close_cycle_policies DROP CONSTRAINT IF EXISTS chk_policy_cutoff_rule;
ALTER TABLE closing.close_cycle_policies DROP CONSTRAINT IF EXISTS close_cycle_policies_cutoff_rule_check;
