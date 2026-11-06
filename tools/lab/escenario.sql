-- LAB ONLY. Three accounts the local stack never projected into closing (they were activated before the
-- cutoff engine existed) get a profile with a product billing cycle, so the lab shows weekly, biweekly
-- and fixed-day monthly statements next to the monthly ones already scheduled for 30 Nov.
-- Run AFTER the lab's closing-service has started once (its migration 018 adds the billing_* columns).
\set ON_ERROR_STOP on
BEGIN;

-- Credit card, weekly, cuts on Fridays (ISO 5), pays within 3 days, minimum 5 %.
INSERT INTO closing.account_close_profiles
      (credit_account_id, obligor_party_id, product_type, product_behavior, status, activated_on, payment_frequency,
       term_periods, nominal_rate, principal_balance, total_debt, days_delinquent, current_cycle, next_cutoff_date,
       last_balance_version, updated_at,
       billing_cutoff_rule, billing_cutoff_day, billing_due_offset_days, billing_grace_days, billing_min_payment_rate, billing_shift)
SELECT a.credit_account_id, a.obligor_party_id, a.product_type, 'REVOLVING', 'ACTIVE', a.activated_at::date, 'WEEKLY',
       NULL, a.nominal_rate, a.principal_balance,
       a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance, a.days_delinquent, 0, DATE '2026-11-06',
       a.balance_version, now(),
       'DAY_OF_WEEK', 5, 3, 3, 0.0500, 'NEXT'
  FROM credit_portfolio.credit_accounts a
 WHERE a.credit_account_id = '4e98edfd-631b-4d58-8e73-b9a01b64812b'
ON CONFLICT (credit_account_id) DO NOTHING;
INSERT INTO closing.cutoff_schedules (credit_account_id, cycle_number, cutoff_date, payment_due_date, status)
VALUES ('4e98edfd-631b-4d58-8e73-b9a01b64812b', 1, DATE '2026-11-06', DATE '2026-11-09', 'SCHEDULED')
ON CONFLICT DO NOTHING;

-- Credit card, biweekly from the first cutoff, pays within 5 days, minimum 10 %.
INSERT INTO closing.account_close_profiles
      (credit_account_id, obligor_party_id, product_type, product_behavior, status, activated_on, payment_frequency,
       term_periods, nominal_rate, principal_balance, total_debt, days_delinquent, current_cycle, next_cutoff_date,
       last_balance_version, updated_at,
       billing_cutoff_rule, billing_cutoff_day, billing_due_offset_days, billing_grace_days, billing_min_payment_rate, billing_shift)
SELECT a.credit_account_id, a.obligor_party_id, a.product_type, 'REVOLVING', 'ACTIVE', DATE '2026-10-30', 'BIWEEKLY',
       NULL, a.nominal_rate, a.principal_balance,
       a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance, a.days_delinquent, 0, DATE '2026-11-13',
       a.balance_version, now(),
       'CYCLE_FROM_ACTIVATION', NULL, 5, 3, 0.1000, 'NEXT'
  FROM credit_portfolio.credit_accounts a
 WHERE a.credit_account_id = '7a1beddf-f48c-4b08-b327-4bb56aec865f'
ON CONFLICT (credit_account_id) DO NOTHING;
INSERT INTO closing.cutoff_schedules (credit_account_id, cycle_number, cutoff_date, payment_due_date, status)
VALUES ('7a1beddf-f48c-4b08-b327-4bb56aec865f', 1, DATE '2026-11-13', DATE '2026-11-18', 'SCHEDULED')
ON CONFLICT DO NOTHING;

-- Distributor line, monthly on the 15th (16 Nov: the 15th is a Sunday), 15 days to pay, minimum 5 %.
INSERT INTO closing.account_close_profiles
      (credit_account_id, obligor_party_id, product_type, product_behavior, status, activated_on, payment_frequency,
       term_periods, nominal_rate, principal_balance, total_debt, days_delinquent, current_cycle, next_cutoff_date,
       last_balance_version, updated_at,
       billing_cutoff_rule, billing_cutoff_day, billing_due_offset_days, billing_grace_days, billing_min_payment_rate, billing_shift)
SELECT a.credit_account_id, a.obligor_party_id, a.product_type, 'REVOLVING', 'ACTIVE', a.activated_at::date, 'MONTHLY',
       NULL, a.nominal_rate, a.principal_balance,
       a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance, a.days_delinquent, 0, DATE '2026-11-16',
       a.balance_version, now(),
       'DAY_OF_MONTH', 15, 15, 3, 0.0500, 'NEXT'
  FROM credit_portfolio.credit_accounts a
 WHERE a.credit_account_id = '4eb18272-e176-4098-91c4-1d21c0da4ea7'
ON CONFLICT (credit_account_id) DO NOTHING;
INSERT INTO closing.cutoff_schedules (credit_account_id, cycle_number, cutoff_date, payment_due_date, status)
VALUES ('4eb18272-e176-4098-91c4-1d21c0da4ea7', 1, DATE '2026-11-16', DATE '2026-12-01', 'SCHEDULED')
ON CONFLICT DO NOTHING;

SELECT left(credit_account_id::text, 8) AS cuenta, payment_frequency, billing_cutoff_rule, billing_cutoff_day, next_cutoff_date
  FROM closing.account_close_profiles WHERE billing_cutoff_rule IS NOT NULL ORDER BY 1;
COMMIT;
