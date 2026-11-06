--liquibase formatted sql
--changeset credit-product:024-comision-de-distribuidora
--comment The distributor's commission as product configuration: a flat share of what she collects, by the day she pays, netted from what she delivers; placements from 12 to 24 fortnights.

-- The market's rule for a distributor: 18 % of everything her clients pay her (principal and interest), the same every
-- fortnight, if she delivers by the statement's due date; 14 % a day later, 11 % two days, 7 % three, nothing after.
-- It is deducted from what she delivers. The portfolio pays it out of the placement's interest and never out of its
-- capital: French interest comes early, so the interest a placement has paid always covers the flat commission paid
-- so far. Below 12 fortnights the interest does not cover 18 % of what is collected (at 6 fortnights that share is
-- 155 % of the interest), so the line places from 12 to 24 fortnights, in steps of 2: 12 payments are 12 fortnights.
ALTER TABLE credit_product.credit_product_definitions
    ADD COLUMN IF NOT EXISTS distributor_commission JSONB;

UPDATE credit_product.credit_product_definitions
   SET distributor_commission = '{"basis":"COLLECTED_TOTAL","rate":0.18,"tiers":[{"daysLate":0,"rate":0.18},{"daysLate":1,"rate":0.14},{"daysLate":2,"rate":0.11},{"daysLate":3,"rate":0.07}],"settlement":"NETTED"}'::jsonb,
       min_term     = 12,
       max_term     = 24,
       default_term = 12,
       term_step    = 2,
       capabilities = capabilities || jsonb_build_object('opcionesDePago',
                          coalesce(capabilities->'opcionesDePago', '{}'::jsonb) || '{"deferralMinTerm": 12, "deferralDefaultTerm": 12, "deferralMaxTerm": 24}'::jsonb)
 WHERE product_type = 'DISTRIBUTOR_LINE';
