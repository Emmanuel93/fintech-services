-- LAB ONLY (rehearsal). Repairs the two defects that made the portfolio's ledger (balance_events) and its
-- balances (credit_accounts) disagree, found on 2026-11-02:
--
--   1) The activation never wrote a ledger entry: CreditAccount.activate() set the principal of a loan
--      without a balance_event, so every loan's ledger starts AFTER the money was lent (100 loans,
--      $6,384,000). The opening-fee event that follows already carries the principal in its after-balances,
--      which is how the missing amount is measured here. Fixed in code (ACTIVATION_DISBURSEMENT event).
--   2) On 31 Oct 2026 (02:18–08:32) the old accrual ran several overlapping attempts per account; apply()
--      read, mutated and saved the account without a lock, so the last writer won: 113 interest events
--      (and their IVA) were written to the ledger while their balance update was overwritten. All 113 are
--      phantoms as well (charges rolled them back). Fixed in code (row lock in apply()).
--
-- Rules applied: a phantom that moved the balances is removed from both; a phantom whose balance update
-- was lost is removed from the ledger only; the missing activation entries are inserted. At the end every
-- live account must satisfy sum(balance_events) = principal + interest + IVA + penalty, or the script aborts.
-- In the real database the same corrections go through events (charge-reversed per phantom, one activation
-- entry per loan) so audit and accounting see them; this SQL is the rehearsal of the outcome.
\set ON_ERROR_STOP on
BEGIN;

-- ── Ledger positions of every event, with the balance step each one really produced ────────────────
CREATE TEMP TABLE ev AS
SELECT credit_account_id, balance_event_id, applied_at, value_date, event_type, delta_amount, source_event_id,
       principal_after, interest_after, penalty_after,
       principal_after + interest_after + penalty_after AS after3,
       lag(principal_after + interest_after + penalty_after) OVER (PARTITION BY credit_account_id ORDER BY applied_at, balance_event_id) AS prev_after3,
       lag(event_type) OVER (PARTITION BY credit_account_id ORDER BY applied_at, balance_event_id) AS prev_type,
       lag(balance_event_id) OVER (PARTITION BY credit_account_id ORDER BY applied_at, balance_event_id) AS prev_id
  FROM credit_portfolio.balance_events;

-- ── 1) Activation entries ───────────────────────────────────────────────────────────────────────────
CREATE TEMP TABLE alta AS
SELECT e.credit_account_id, e.after3 - coalesce(e.prev_after3, 0) - e.delta_amount AS capital, e.applied_at AS fee_at, a.activated_at
  FROM ev e JOIN credit_portfolio.credit_accounts a USING (credit_account_id)
 WHERE e.event_type = 'CHARGE_OPENING_FEE' AND e.after3 - coalesce(e.prev_after3, 0) - e.delta_amount > 0.005;
SELECT 'altas sin asiento: ' || count(*) || ' por ' || sum(capital) FROM alta;

INSERT INTO credit_portfolio.balance_events
       (balance_event_id, credit_account_id, source_event_id, event_type, delta_amount, principal_after, interest_after,
        penalty_after, available_after, applied_at, value_date)
SELECT gen_random_uuid(), credit_account_id, 'activation:' || credit_account_id, 'ACTIVATION_DISBURSEMENT', capital,
       capital, 0, 0, NULL, least(fee_at, coalesce(activated_at, fee_at)) - interval '1 millisecond',
       (least(fee_at, coalesce(activated_at, fee_at)) AT TIME ZONE 'America/Mexico_City')::date
  FROM alta
ON CONFLICT (source_event_id) DO NOTHING;

-- ── 2) Phantoms: lost-update ones (ledger only) vs the rest (ledger and balances) ──────────────────
CREATE TEMP TABLE phantom AS
SELECT e.balance_event_id, e.credit_account_id, e.event_type, e.delta_amount,
       -- the interest event whose step is missing, and the IVA event right after it
       (e.event_type = 'CHARGE_ORDINARY_INTEREST' AND abs(e.delta_amount - (e.after3 - coalesce(e.prev_after3, 0))) > 0.005) AS lost
  FROM ev e
  LEFT JOIN charges.charge_records cr ON cr.charge_id::text = e.source_event_id
 WHERE e.event_type IN ('CHARGE_ORDINARY_INTEREST', 'CHARGE_IVA', 'CHARGE_MORATORIUM_INTEREST') AND cr.charge_id IS NULL;
UPDATE phantom p SET lost = true
  FROM ev e JOIN phantom q ON q.balance_event_id = e.prev_id AND q.lost
 WHERE p.balance_event_id = e.balance_event_id AND p.event_type = 'CHARGE_IVA' AND e.prev_type = 'CHARGE_ORDINARY_INTEREST';

SELECT 'fantasmas: ' || count(*) || ' eventos, de los cuales ' || count(*) FILTER (WHERE lost) || ' nunca movieron saldos (' || coalesce(sum(delta_amount) FILTER (WHERE lost), 0) || ')' FROM phantom;

WITH t AS (
    SELECT credit_account_id,
           coalesce(sum(delta_amount) FILTER (WHERE event_type = 'CHARGE_ORDINARY_INTEREST' AND NOT lost), 0)   AS di,
           coalesce(sum(delta_amount) FILTER (WHERE event_type = 'CHARGE_IVA' AND NOT lost), 0)                 AS diva,
           coalesce(sum(delta_amount) FILTER (WHERE event_type = 'CHARGE_MORATORIUM_INTEREST' AND NOT lost), 0) AS dpen
      FROM phantom GROUP BY credit_account_id)
UPDATE credit_portfolio.credit_accounts a
   SET accrued_interest_balance = a.accrued_interest_balance - t.di,
       iva_balance              = a.iva_balance - t.diva,
       penalty_balance          = a.penalty_balance - t.dpen,
       balance_version          = a.balance_version + 1,
       updated_at               = now()
  FROM t
 WHERE t.credit_account_id = a.credit_account_id;

DELETE FROM credit_portfolio.balance_events be USING phantom p WHERE p.balance_event_id = be.balance_event_id;

-- ── Closing's projection follows the corrected balances (in production, through balance-updated) ───
UPDATE closing.account_close_profiles p
   SET total_debt = a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance,
       principal_balance = a.principal_balance,
       updated_at = now()
  FROM credit_portfolio.credit_accounts a
 WHERE a.credit_account_id = p.credit_account_id;

-- ── IVA residue ────────────────────────────────────────────────────────────────────────────────────
-- The ledger has no after-balance for IVA, so whether the IVA entry that followed a lost interest entry
-- also lost its balance update cannot be read from the row; it is assumed lost. Where it was not (a few
-- accounts, cents), the ledger is the record and the IVA balance follows it. Anything bigger is not a
-- rounding residue and aborts below.
CREATE TEMP TABLE residuo AS
SELECT a.credit_account_id,
       coalesce(e.s, 0) - (a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance) AS gap
  FROM credit_portfolio.credit_accounts a
  LEFT JOIN (SELECT credit_account_id, sum(delta_amount) s FROM credit_portfolio.balance_events GROUP BY 1) e USING (credit_account_id)
 WHERE a.status IN ('ACTIVE', 'SUSPENDED', 'RESTRUCTURED') AND abs(coalesce(e.s, 0) - (a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance)) > 0.005;
SELECT 'residuos de IVA: ' || count(*) || ' cuentas, ' || coalesce(sum(gap), 0) FROM residuo;
UPDATE credit_portfolio.credit_accounts a
   SET iva_balance = a.iva_balance + r.gap, balance_version = a.balance_version + 1, updated_at = now()
  FROM residuo r
 WHERE r.credit_account_id = a.credit_account_id AND abs(r.gap) <= 5.00;
UPDATE closing.account_close_profiles p
   SET total_debt = a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance, updated_at = now()
  FROM credit_portfolio.credit_accounts a JOIN residuo r USING (credit_account_id)
 WHERE a.credit_account_id = p.credit_account_id;

-- ── The identity that must hold from now on ───────────────────────────────────────────────────────
DO $$
DECLARE off_count int; off_total numeric;
BEGIN
    SELECT count(*), coalesce(sum(abs(d)), 0) INTO off_count, off_total FROM (
        SELECT coalesce(e.s, 0) - (a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance) AS d
          FROM credit_portfolio.credit_accounts a
          LEFT JOIN (SELECT credit_account_id, sum(delta_amount) s FROM credit_portfolio.balance_events GROUP BY 1) e USING (credit_account_id)
         WHERE a.status IN ('ACTIVE', 'SUSPENDED', 'RESTRUCTURED')) x WHERE abs(d) > 0.01;
    IF off_count > 0 THEN
        RAISE EXCEPTION 'libro y saldos siguen sin cuadrar en % cuentas (%): no se confirma la reparación', off_count, off_total;
    END IF;
    RAISE NOTICE 'libro = saldos en todas las cuentas vivas';
END $$;

COMMIT;
