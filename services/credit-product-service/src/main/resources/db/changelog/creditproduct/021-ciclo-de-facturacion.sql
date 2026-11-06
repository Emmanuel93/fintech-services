--liquibase formatted sql
--changeset credit-product:021-ciclo-de-facturacion
--comment The billing cycle (cutoff rule, due offset, grace, minimum payment) is product configuration, not a closing-service seed.

-- Until now the cutoff rule, the days to pay, the grace period and the minimum payment lived in
-- closing.close_cycle_policies, seeded by hand per product type; the product only declared its
-- payment frequency, and even that never reached the portfolio. From here on the product owns the
-- whole cycle and publishes it on activation; closing derives each account's calendar from it.
ALTER TABLE credit_product.credit_product_definitions
    ADD COLUMN IF NOT EXISTS billing_cycle JSONB;

-- Backfill with exactly what closing applied so far, so no existing account changes its dates.
UPDATE credit_product.credit_product_definitions
   SET billing_cycle = CASE
        WHEN product_type IN ('PERSONAL_LOAN', 'PAYROLL_LOAN', 'MICRO_LOAN', 'SME_LOAN', 'GROUP_LOAN') THEN
             '{"cutoffRule":"INSTALLMENT_DUE_DATE","cutoffDay":null,"paymentDueOffsetDays":3,"graceDays":3,"minimumPaymentRate":null,"nonBusinessDayShift":"NEXT"}'::jsonb
        WHEN product_type = 'DISTRIBUTOR_LINE' THEN
             '{"cutoffRule":"CYCLE_FROM_ACTIVATION","cutoffDay":null,"paymentDueOffsetDays":15,"graceDays":3,"minimumPaymentRate":0.0500,"nonBusinessDayShift":"NEXT"}'::jsonb
        ELSE '{"cutoffRule":"CYCLE_FROM_ACTIVATION","cutoffDay":null,"paymentDueOffsetDays":20,"graceDays":3,"minimumPaymentRate":0.0500,"nonBusinessDayShift":"NEXT"}'::jsonb
   END
 WHERE billing_cycle IS NULL;

ALTER TABLE credit_product.credit_product_definitions
    ALTER COLUMN billing_cycle SET NOT NULL;
