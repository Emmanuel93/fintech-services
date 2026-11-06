--liquibase formatted sql
--changeset credit-product:023-distribuidora-quincenal
--comment A distributor line lives by fortnights: micro-loans to its clients at 95 %, cut on the 6th and the 21st, paid by the 18th and the 3rd.

-- 016 put the line on BIWEEKLY: every fourteen days from the activation, 26 a year. The months came out with two or
-- three cutoffs on drifting days, and each placement fell due on its own dates (fourteen days after it was placed),
-- so its interest landed between cutoffs instead of at them. The business works by fortnights on fixed days, as the
-- reference system (cartera-empresa) runs them: it cuts on the 6th and on the 21st, and each statement is paid by the
-- 18th and by the 3rd of the next month, after the clients' paydays (the 15th and the last day). SEMI_MONTHLY is that
-- frequency (24 a year), and the SEMI_MONTHLY cutoff rule cuts on its two cutoffDays for every line alike.
ALTER TABLE credit_product.credit_product_definitions DROP CONSTRAINT IF EXISTS ck_default_payment_frequency;
ALTER TABLE credit_product.credit_product_definitions ADD CONSTRAINT ck_default_payment_frequency
    CHECK (default_payment_frequency IS NULL
           OR default_payment_frequency IN ('WEEKLY', 'BIWEEKLY', 'SEMI_MONTHLY', 'MONTHLY'));

ALTER TABLE credit_product.credit_product_payment_frequencies DROP CONSTRAINT IF EXISTS ck_payment_frequency_val;
ALTER TABLE credit_product.credit_product_payment_frequencies ADD CONSTRAINT ck_payment_frequency_val
    CHECK (payment_frequency IN ('WEEKLY', 'BIWEEKLY', 'SEMI_MONTHLY', 'MONTHLY'));

-- The product, as the business defines it: a micro-loan to each client of the distributor, from $1,000 to $10,000 in
-- steps of $500, paid in 6 to 24 fortnights (12 payments are 12 fortnights), at 95 % a year: 95 / 24 per fortnight on
-- the placement's balance, charged at the cutoff the fortnight ends on. The statement of the 6th falls due on the 18th
-- and the one of the 21st on the 3rd; the grace ends the day before the next cutoff (the 20th and the 5th). It asks for
-- the fortnight's payments and charges: there is no minimum payment. The moratorium stays where it was (36 % a year on
-- what is overdue, on top of the ordinary interest).
UPDATE credit_product.credit_product_definitions
   SET default_payment_frequency = 'SEMI_MONTHLY',
       nominal_rate_annual       = 0.9500,
       min_amount                = 1000.00,
       max_amount                = 10000.00,
       amount_step               = 500,
       min_term                  = 6,
       max_term                  = 24,
       default_term              = 12,
       term_step                 = 2,
       billing_cycle             = '{"cutoffRule":"SEMI_MONTHLY","cutoffDay":null,"paymentDueOffsetDays":0,"graceDays":2,"minimumPaymentRate":null,"nonBusinessDayShift":"NEXT","interestAccrual":"PER_PERIOD","cutoffDays":[6,21],"paymentDueDays":[18,3]}'::jsonb
 WHERE product_definition_id = 'a1000001-0000-0000-0000-000000000004';

DELETE FROM credit_product.credit_product_payment_frequencies
 WHERE product_definition_id = 'a1000001-0000-0000-0000-000000000004'
   AND payment_frequency <> 'SEMI_MONTHLY';
INSERT INTO credit_product.credit_product_payment_frequencies (product_definition_id, payment_frequency)
VALUES ('a1000001-0000-0000-0000-000000000004', 'SEMI_MONTHLY')
ON CONFLICT DO NOTHING;

-- The console shows the rate cards next to the product: the line's bands carry the same price now.
UPDATE credit_product.rate_cards
   SET nominal_rate = 0.9500, moratorium_rate = 0.3600
 WHERE product_definition_id = 'a1000001-0000-0000-0000-000000000004';
