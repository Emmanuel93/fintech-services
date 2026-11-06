-- LAB ONLY. Rehearsal of the phantom-charge repair on the lab's copy of the database.
--
-- A phantom is a charge the portfolio applied (credit_portfolio.balance_events) that charges never
-- saved (no charge_records row with that id): they came from messages published before a failed
-- commit, mostly by the E2E's portfolio-wide rewind (29 Oct – 1 Nov 2026). Here they are removed in
-- place and the balances corrected; closing's projection is realigned the same way.
--
-- In the real database this must NOT be done like this: each phantom is reversed through a
-- charges.charge-reversed event (one per phantom, same id scheme as the 31 Oct repair), so cartera,
-- closing, audit and accounting all see the reversal and the trail stays complete.
\set ON_ERROR_STOP on
BEGIN;

CREATE TEMP TABLE phantom AS
SELECT be.balance_event_id, be.credit_account_id, be.event_type, be.delta_amount
  FROM credit_portfolio.balance_events be
  LEFT JOIN charges.charge_records cr ON cr.charge_id::text = be.source_event_id
 WHERE be.event_type IN ('CHARGE_ORDINARY_INTEREST', 'CHARGE_IVA', 'CHARGE_MORATORIUM_INTEREST')
   AND cr.charge_id IS NULL;

SELECT 'fantasmas: ' || count(*) || ' eventos en ' || count(DISTINCT credit_account_id) || ' cuentas, ' || sum(delta_amount) AS antes FROM phantom;

WITH t AS (
    SELECT credit_account_id,
           coalesce(sum(delta_amount) FILTER (WHERE event_type = 'CHARGE_ORDINARY_INTEREST'), 0)   AS di,
           coalesce(sum(delta_amount) FILTER (WHERE event_type = 'CHARGE_IVA'), 0)                 AS diva,
           coalesce(sum(delta_amount) FILTER (WHERE event_type = 'CHARGE_MORATORIUM_INTEREST'), 0) AS dpen
      FROM phantom GROUP BY credit_account_id)
UPDATE credit_portfolio.credit_accounts a
   SET accrued_interest_balance = greatest(a.accrued_interest_balance - t.di, 0),
       iva_balance              = greatest(a.iva_balance - t.diva, 0),
       penalty_balance          = greatest(a.penalty_balance - t.dpen, 0),
       balance_version          = a.balance_version + 1,
       updated_at               = now()
  FROM t
 WHERE t.credit_account_id = a.credit_account_id;

DELETE FROM credit_portfolio.balance_events be USING phantom p WHERE p.balance_event_id = be.balance_event_id;

-- Closing's projection of total debt follows the corrected balances (in production, through balance-updated).
UPDATE closing.account_close_profiles p
   SET total_debt = a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance,
       principal_balance = a.principal_balance,
       updated_at = now()
  FROM credit_portfolio.credit_accounts a
 WHERE a.credit_account_id = p.credit_account_id
   AND a.credit_account_id IN (SELECT DISTINCT credit_account_id FROM phantom);

SELECT 'quedan sin registro en cargos: ' || count(*) AS despues
  FROM credit_portfolio.balance_events be
  LEFT JOIN charges.charge_records cr ON cr.charge_id::text = be.source_event_id
 WHERE be.event_type IN ('CHARGE_ORDINARY_INTEREST', 'CHARGE_IVA', 'CHARGE_MORATORIUM_INTEREST') AND cr.charge_id IS NULL;

COMMIT;
