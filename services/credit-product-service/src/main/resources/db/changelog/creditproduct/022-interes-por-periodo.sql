--liquibase formatted sql
--changeset credit-product:022-interes-por-periodo
--comment How ordinary interest is charged is product configuration: daily on the balance, or per period as each plan sets it.

-- Until now every product accrued interest daily on the outstanding principal (rate / 360 a day). A distributor line
-- does not work that way: each placement is born with its own amortization plan (French, biweekly), and the interest
-- of each period is the plan's (rate / 26 on the placement's balance), charged the day the period falls due. Charging
-- it daily on the line's principal asked 14 x rate / 360 for a 14-day period instead of rate / 26, and charged it
-- day by day before the period was owed. Cards, lines used for oneself and loans keep accruing daily.
UPDATE credit_product.credit_product_definitions
   SET billing_cycle = billing_cycle || jsonb_build_object('interestAccrual',
       CASE WHEN product_type = 'DISTRIBUTOR_LINE' THEN 'PER_PERIOD' ELSE 'DAILY' END)
 WHERE billing_cycle IS NOT NULL
   AND billing_cycle->>'interestAccrual' IS NULL;
