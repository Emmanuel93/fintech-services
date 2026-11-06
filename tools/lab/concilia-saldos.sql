-- Reconciliation queries (read-only). Run against the local stack or the lab:
--   docker exec -i fintech-services-postgres-1 psql -U fintech -d fintech < tools/lab/concilia-saldos.sql
--
-- 1) The portfolio's own ledger vs its balances: for every live account, the sum of balance_events
--    must equal principal + interest + IVA + penalty. Any difference is drift nobody explained.
WITH e AS (
    SELECT credit_account_id, sum(delta_amount) AS events FROM credit_portfolio.balance_events GROUP BY 1)
SELECT 'libro vs saldos' AS check_name,
       count(*) FILTER (WHERE abs(coalesce(e.events, 0) - (a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance)) > 0.01) AS accounts_off,
       count(*) AS accounts,
       round(sum(abs(coalesce(e.events, 0) - (a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance))), 2) AS total_abs_difference
  FROM credit_portfolio.credit_accounts a
  LEFT JOIN e USING (credit_account_id)
 WHERE a.status IN ('ACTIVE', 'SUSPENDED', 'RESTRUCTURED');

-- 2) Charges the portfolio applied that charges never saved (phantoms).
SELECT 'fantasmas' AS check_name, count(*) AS events, count(DISTINCT be.credit_account_id) AS accounts, coalesce(sum(be.delta_amount), 0) AS amount
  FROM credit_portfolio.balance_events be
  LEFT JOIN charges.charge_records cr ON cr.charge_id::text = be.source_event_id
 WHERE be.event_type IN ('CHARGE_ORDINARY_INTEREST', 'CHARGE_IVA', 'CHARGE_MORATORIUM_INTEREST') AND cr.charge_id IS NULL;

-- 3) Charges saved that never reached the portfolio (missing).
SELECT 'faltantes' AS check_name, count(*) AS charges, coalesce(sum(cr.amount), 0) AS amount
  FROM charges.charge_records cr
  LEFT JOIN credit_portfolio.balance_events be ON be.source_event_id = cr.charge_id::text
 WHERE cr.status = 'APPLIED' AND be.balance_event_id IS NULL;

-- 4) Each day's snapshot principal vs the interest charges accrues on it: basis × rate / 360 = amount.
SELECT 'base de la foto vs cargo' AS check_name, count(*) AS charges_off
  FROM charges.charge_records cr
  JOIN charges.accrual_units u ON u.credit_account_id = cr.credit_account_id AND u.business_date = cr.accrual_date
 WHERE cr.charge_type = 'ORDINARY_INTEREST' AND cr.day_close_date IS NOT NULL AND u.basis_principal IS NOT NULL
   AND (cr.basis <> u.basis_principal OR abs(round(u.basis_principal * cr.rate / 360, 2) - cr.amount) > 0.01);

-- 5) Sealed cutoffs vs the portfolio's snapshot of that day: the principal must match.
SELECT 'corte vs foto' AS check_name, count(*) AS cutoffs_off
  FROM closing.cutoff_schedules c
  JOIN credit_portfolio.account_day_snapshots f ON f.credit_account_id = c.credit_account_id AND f.business_date = c.cutoff_date
 WHERE c.status = 'SEALED' AND c.principal_at_cutoff <> f.principal;

-- 6) Statements vs the ledger of their period: previous balance + charges + dispositions − payments = balance at cutoff.
WITH per AS (
    SELECT s.statement_id, s.credit_account_id, s.period_start, s.cutoff_date, s.balance_at_cutoff,
           (SELECT coalesce(sum(delta_amount), 0) FROM credit_portfolio.balance_events b
             WHERE b.credit_account_id = s.credit_account_id AND b.value_date < s.period_start) AS opening,
           (SELECT coalesce(sum(delta_amount), 0) FROM credit_portfolio.balance_events b
             WHERE b.credit_account_id = s.credit_account_id AND b.value_date BETWEEN s.period_start AND s.cutoff_date) AS movements
      FROM statement.statements s WHERE s.status = 'GENERATED')
SELECT 'estado de cuenta vs libro' AS check_name, count(*) FILTER (WHERE abs(opening + movements - balance_at_cutoff) > 0.01) AS statements_off, count(*) AS statements
  FROM per;
