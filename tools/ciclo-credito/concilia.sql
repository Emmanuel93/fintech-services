-- Reconciliation of the whole chain (read-only). One row per check, always the same three columns:
--   comprobacion | fuera | detalle
-- `fuera` is what does not reconcile (accounts, entries, statements or pesos): every row must show 0.
-- The verdict (veredicto.py) reads this same file and fails the run on any non-zero row.
--   docker exec -i fintech-services-postgres-1 psql -U fintech -d fintech < tools/ciclo-credito/concilia.sql

-- ── Portfolio subledger ─────────────────────────────────────────────────────────────────────────

-- 1) The portfolio's ledger vs its balances, every account whatever its status: a settled account's ledger
--    nets to zero, and a written-off one's too (the write-off entry carries the debt it removed).
WITH e AS (SELECT credit_account_id, sum(delta_amount) AS events FROM credit_portfolio.balance_events GROUP BY 1)
SELECT '01 libro de cartera vs saldos' AS comprobacion,
       count(*) FILTER (WHERE abs(coalesce(e.events, 0) - (a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance)) > 0.01) AS fuera,
       count(*) || ' cuentas (' || string_agg(DISTINCT a.status, ', ') || '), diferencia absoluta ' ||
       round(coalesce(sum(abs(coalesce(e.events, 0) - (a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance))), 0), 2) AS detalle
  FROM credit_portfolio.credit_accounts a LEFT JOIN e USING (credit_account_id);

-- 2) Charges applied by the portfolio that charges never saved (phantoms).
SELECT '02 cargos fantasma (cartera sin cargo)' AS comprobacion, count(*) AS fuera,
       'importe ' || coalesce(sum(be.delta_amount), 0) AS detalle
  FROM credit_portfolio.balance_events be
  LEFT JOIN charges.charge_records cr ON cr.charge_id::text = be.source_event_id
 WHERE be.event_type IN ('CHARGE_ORDINARY_INTEREST', 'CHARGE_IVA', 'CHARGE_MORATORIUM_INTEREST') AND cr.charge_id IS NULL;

-- 3) Charges saved that never reached the portfolio (reversed and waived ones included: they were applied first).
SELECT '03 cargos sin cartera' AS comprobacion, count(*) AS fuera, 'importe ' || coalesce(sum(cr.amount), 0) AS detalle
  FROM charges.charge_records cr
  LEFT JOIN credit_portfolio.balance_events be ON be.source_event_id = cr.charge_id::text
 WHERE be.balance_event_id IS NULL;

-- 3b) Reversals and waivers of a charge that the portfolio never applied: they carry their own key
--     (CHARGE_REVERSED:<id> / CHARGE_WAIVED:<id>), so the original charge's entry cannot hide them.
SELECT '03b reversas y condonaciones sin cartera' AS comprobacion, count(*) AS fuera,
       count(*) FILTER (WHERE cr.status = 'WAIVED') || ' condonaciones, ' || count(*) FILTER (WHERE cr.status = 'REVERSED') || ' reversas' AS detalle
  FROM charges.charge_records cr
 WHERE cr.status IN ('REVERSED', 'WAIVED')
   AND NOT EXISTS (SELECT 1 FROM credit_portfolio.balance_events be
                    WHERE be.source_event_id IN ('CHARGE_REVERSED:' || cr.charge_id, 'CHARGE_WAIVED:' || cr.charge_id));

-- 3c) Returned payments that the portfolio never restored (payments says REVERSED, the ledger still has the payment).
SELECT '03c devoluciones de pago sin cartera' AS comprobacion, count(*) AS fuera,
       'importe ' || coalesce(sum(po.amount), 0) AS detalle
  FROM payments.payment_orders po
 WHERE po.status = 'REVERSED'
   AND EXISTS (SELECT 1 FROM credit_portfolio.balance_events be WHERE be.source_event_id = po.payment_order_id::text)
   AND NOT EXISTS (SELECT 1 FROM credit_portfolio.balance_events be WHERE be.source_event_id = 'PAYMENT_RETURNED:' || po.payment_order_id);

-- 3d) Payments still waiting for the portfolio at the end of the run.
SELECT '03d pagos sin confirmar' AS comprobacion, count(*) AS fuera, 'importe ' || coalesce(sum(amount), 0) AS detalle
  FROM payments.payment_orders WHERE status = 'PENDING';

-- ── Nightly chain ───────────────────────────────────────────────────────────────────────────────

-- 4) Daily accrual: the snapshot's principal × rate / 360 = the day's interest charge.
SELECT '04 base de la foto vs cargo (interés diario)' AS comprobacion, count(*) AS fuera, count(*) || ' cargos desfasados' AS detalle
  FROM charges.charge_records cr
  JOIN charges.accrual_units u ON u.credit_account_id = cr.credit_account_id AND u.business_date = cr.accrual_date
  JOIN charges.accrual_schedules s ON s.credit_account_id = cr.credit_account_id
 WHERE cr.charge_type = 'ORDINARY_INTEREST' AND cr.day_close_date IS NOT NULL AND u.basis_principal IS NOT NULL
   AND s.interest_accrual = 'DAILY'
   AND (cr.basis <> u.basis_principal OR abs(round(u.basis_principal * cr.rate / 360, 2) - cr.amount) > 0.01);

-- 4e) Per-period accrual (a distributor's placements): each day's charge is the interest the plans set for the
--     periods falling due that day (the snapshot's figure), and nothing is charged on the other days. A placement
--     whose SPEI never happened leaves its plan CANCELLED (D63): it bears no interest, as the snapshot already says.
--     On a revolving line, a placement paid off before one of its periods owes no interest for it (D06 settles the
--     whole line in December and places again in January): the oracle reads that from the plan itself, every
--     installment of the placement PAID before the period's date, not from how the portfolio marks it.
WITH plan AS (
    SELECT coalesce(d.credit_account_id, i.schedule_id) AS credit_account_id, coalesce(i.original_due_date, i.due_date) AS due_date,
           sum(i.interest_amount) AS interes
      FROM credit_portfolio.installments i
      LEFT JOIN credit_portfolio.dispositions d ON d.disposition_id = i.schedule_id
      LEFT JOIN credit_portfolio.credit_accounts a ON a.credit_account_id = coalesce(d.credit_account_id, i.schedule_id)
     WHERE i.status NOT IN ('REPLACED', 'CANCELLED') AND i.interest_amount > 0
       AND NOT (coalesce(a.product_behavior = 'REVOLVING', false)
                AND EXISTS (SELECT 1 FROM credit_portfolio.installments y
                             WHERE y.schedule_id = i.schedule_id AND y.status NOT IN ('REPLACED', 'CANCELLED')
                            HAVING bool_and(y.status = 'PAID')
                               AND max(y.paid_on) < coalesce(i.original_due_date, i.due_date)))
     GROUP BY 1, 2),
     cobrado AS (
    SELECT cr.credit_account_id, cr.accrual_date, sum(cr.amount) AS interes
      FROM charges.charge_records cr
      JOIN charges.accrual_schedules s ON s.credit_account_id = cr.credit_account_id AND s.interest_accrual = 'PER_PERIOD'
     WHERE cr.charge_type = 'ORDINARY_INTEREST' AND cr.status = 'APPLIED' AND cr.day_close_date IS NOT NULL
     GROUP BY 1, 2)
SELECT '04e interés por periodo: cargo vs plan' AS comprobacion,
       count(*) FILTER (WHERE abs(coalesce(c.interes, 0) - coalesce(u.basis_scheduled_interest, 0)) > 0.01
                           OR (u.basis_principal > 0 AND abs(coalesce(c.interes, 0) - coalesce(p.interes, 0)) > 0.01)) AS fuera,
       count(*) FILTER (WHERE c.interes > 0) || ' periodos cobrados, ' || coalesce(sum(c.interes), 0) || ' de interés' AS detalle
  FROM charges.accrual_units u
  JOIN charges.accrual_schedules s ON s.credit_account_id = u.credit_account_id AND s.interest_accrual = 'PER_PERIOD'
  LEFT JOIN cobrado c ON c.credit_account_id = u.credit_account_id AND c.accrual_date = u.business_date
  LEFT JOIN plan p ON p.credit_account_id = u.credit_account_id AND p.due_date = u.business_date
 WHERE u.status = 'DONE';

-- 4b) The basis charges planned is the principal the portfolio's snapshot says bears interest: the principal
--     without the installments still to come of purchases deferred interest-free (meses sin intereses).
SELECT '04b base del plan vs foto de cartera' AS comprobacion,
       count(*) FILTER (WHERE u.basis_principal <> coalesce(f.interest_bearing_principal, f.principal)) AS fuera,
       count(*) FILTER (WHERE f.interest_bearing_principal < f.principal) || ' unidades con meses sin intereses' AS detalle
  FROM charges.accrual_units u
  JOIN credit_portfolio.account_day_snapshots f ON f.credit_account_id = u.credit_account_id AND f.business_date = u.business_date
 WHERE u.basis_principal IS NOT NULL;

-- 4c) Accrual units that did not finish: quarantined, held, failed or still pending.
SELECT '04c unidades de devengo sin terminar' AS comprobacion, count(*) AS fuera,
       coalesce(string_agg(DISTINCT status, ', '), 'todas DONE') AS detalle
  FROM charges.accrual_units WHERE status <> 'DONE';

-- 4d) The interest rate charged is the account's agreed rate on that day: a restructure changes it from its date.
SELECT '04d tasa del cargo vs tasa pactada' AS comprobacion, count(*) AS fuera, count(*) || ' cargos a otra tasa' AS detalle
  FROM charges.charge_records cr
  JOIN charges.accrual_schedules s ON s.credit_account_id = cr.credit_account_id
 WHERE cr.charge_type = 'ORDINARY_INTEREST'
   AND cr.accrual_date >= coalesce((SELECT max(be.value_date) FROM credit_portfolio.balance_events be
                                      WHERE be.credit_account_id = cr.credit_account_id AND be.event_type = 'AGREEMENT_RESTRUCTURE'),
                                   DATE '1900-01-01')
   AND cr.rate <> s.nominal_rate
   AND s.status <> 'CLOSED';

-- 5) Sealed cutoffs vs the snapshot of that day: the principal sealed must be the snapshot's.
SELECT '05 corte vs foto' AS comprobacion,
       count(*) FILTER (WHERE f.principal IS NULL OR c.principal_at_cutoff <> f.principal) AS fuera,
       count(*) || ' cortes sellados' AS detalle
  FROM closing.cutoff_schedules c
  LEFT JOIN credit_portfolio.account_day_snapshots f ON f.credit_account_id = c.credit_account_id AND f.business_date = c.cutoff_date
 WHERE c.status IN ('SEALED', 'PROPAGATED');

-- 5a) Cutoffs that should have happened and did not: scheduled on a day already closed.
SELECT '05a cortes vencidos sin sellar' AS comprobacion, count(*) AS fuera, coalesce(min(c.cutoff_date)::text, '—') AS detalle
  FROM closing.cutoff_schedules c
 WHERE c.status = 'SCHEDULED'
   AND c.cutoff_date <= (SELECT max(business_date) FROM credit_portfolio.day_closes);

-- 5b) What each statement asked of the plans (placements, deferred purchases) is what the portfolio billed in
--     that cycle. A statement assembled before the portfolio billed the previous cycle asked for it again.
WITH facturado AS (
    SELECT d.credit_account_id, i.billed_cycle, sum(i.principal_amount) AS capital
      FROM credit_portfolio.installments i
      JOIN credit_portfolio.dispositions d ON d.disposition_id = i.schedule_id
     WHERE i.billed_cycle IS NOT NULL
     GROUP BY 1, 2)
SELECT '05b estado de cuenta vs facturación de cartera' AS comprobacion,
       count(*) FILTER (WHERE abs(coalesce((s.snapshot->'view'->>'plansPrincipalDue')::numeric, 0) - coalesce(f.capital, 0)) > 0.01) AS fuera,
       count(*) || ' estados revolventes' AS detalle
  FROM statement.statements s
  LEFT JOIN facturado f ON f.credit_account_id = s.credit_account_id AND f.billed_cycle = s.cycle_number
 WHERE s.status = 'GENERATED' AND s.product_behavior = 'REVOLVING';

-- 6) Statements vs the ledger of their period.
WITH per AS (
    SELECT s.statement_id, s.credit_account_id, s.period_start, s.cutoff_date, s.balance_at_cutoff,
           (SELECT coalesce(sum(delta_amount), 0) FROM credit_portfolio.balance_events b
             WHERE b.credit_account_id = s.credit_account_id AND b.value_date < s.period_start) AS opening,
           (SELECT coalesce(sum(delta_amount), 0) FROM credit_portfolio.balance_events b
             WHERE b.credit_account_id = s.credit_account_id AND b.value_date BETWEEN s.period_start AND s.cutoff_date) AS movements
      FROM statement.statements s WHERE s.status = 'GENERATED')
SELECT '06 estado de cuenta vs libro' AS comprobacion, count(*) FILTER (WHERE abs(opening + movements - balance_at_cutoff) > 0.01) AS fuera,
       count(*) || ' estados' AS detalle
  FROM per;

-- 6b) What a revolving statement shows as arrears is what the day snapshot of its cutoff had overdue, plus what the
--     statements asked to pay on the cutoff day itself and its end left unpaid: the statement is issued once the day is
--     over, when that is late too, while the day's snapshot counts it apart (late from the next day). A distributor
--     line's statement falls due on the next one's cutoff, so every missed due date of a distributor lands there. A
--     payment covers only the statements issued before it: paying one statement in full does not cover the next one's
--     minimum (E34: the third statement showed no arrears with the second's minimum unpaid).
--     The statement's arrears is the whole of what the past-due statements still ask; the snapshot keeps that whole
--     (overdue_statements_amount) next to the principal the moratorium runs on (overdue_principal), which is less.
SELECT '06b atraso del estado vs foto del corte' AS comprobacion,
       count(*) FILTER (WHERE abs(coalesce((s.snapshot->'view'->>'arrears')::numeric, 0)
                                  - (coalesce(f.overdue_statements_amount, 0) + coalesce(f.statement_due_unpaid, 0))) > 0.01) AS fuera,
       count(*) || ' estados revolventes' AS detalle
  FROM statement.statements s
  LEFT JOIN credit_portfolio.account_day_snapshots f
         ON f.credit_account_id = s.credit_account_id AND f.business_date = s.cutoff_date
 WHERE s.status = 'GENERATED' AND s.product_behavior = 'REVOLVING';

-- 6a) Statements that were not generated.
SELECT '06a estados de cuenta sin generar' AS comprobacion, count(*) AS fuera,
       coalesce(string_agg(DISTINCT status, ', '), 'todos GENERATED') AS detalle
  FROM statement.statements WHERE status <> 'GENERATED';

-- 7) Every day's snapshot reported zero ledger gaps.
SELECT '07 huecos de libro en la foto diaria' AS comprobacion, coalesce(sum(ledger_gaps), 0) AS fuera, count(*) || ' días' AS detalle
  FROM credit_portfolio.day_snapshots WHERE status = 'DONE';

-- ── Accounting ──────────────────────────────────────────────────────────────────────────────────

-- 8) The interest receivable subledger (1203) is charged with what charges accrued, net of reversals and waivers.
WITH cargos AS (
    SELECT coalesce(sum(amount), 0) AS t FROM charges.charge_records
     WHERE status = 'APPLIED' AND charge_type IN ('ORDINARY_INTEREST', 'MORATORIUM_INTEREST')),
     contab AS (
    SELECT coalesce(sum(amount) FILTER (WHERE debit_account = '1203' AND trigger_event IN ('CHARGE_ORDINARY_INTEREST', 'CHARGE_MORATORIUM_INTEREST')), 0)
         - coalesce(sum(amount) FILTER (WHERE credit_account = '1203' AND trigger_event IN ('CHARGE_REVERSED', 'CHARGE_WAIVED')
                                          AND debit_account IN ('4101', '4102', '5103')), 0) AS t
      FROM accounting.journal_entries)
SELECT '08 intereses: cargos vs contabilidad 1203' AS comprobacion,
       abs(round((SELECT t FROM contab) - (SELECT t FROM cargos), 2)) AS fuera,
       'cargos ' || (SELECT t FROM cargos) || ', contabilidad ' || (SELECT t FROM contab) AS detalle;

-- 9) No unbalanced voucher.
SELECT '09 pólizas descuadradas' AS comprobacion, count(*) AS fuera, count(*) || ' pólizas' AS detalle
  FROM accounting.vouchers WHERE status = 'POSTED' AND abs(total_debit - total_credit) > 0.005;

-- 10) The portfolio's principal equals the 1201 balance in the ledger (written-off accounts carry zero in both).
WITH mayor AS (
    SELECT coalesce(sum(CASE WHEN debit_account = '1201' THEN amount ELSE 0 END), 0)
         - coalesce(sum(CASE WHEN credit_account = '1201' THEN amount ELSE 0 END), 0) AS saldo
      FROM accounting.journal_entries),
     cartera AS (SELECT coalesce(sum(principal_balance), 0) AS saldo FROM credit_portfolio.credit_accounts)
SELECT '10 capital: cartera vs mayor 1201' AS comprobacion,
       abs(round((SELECT saldo FROM mayor) - (SELECT saldo FROM cartera), 2)) AS fuera,
       'cartera ' || (SELECT saldo FROM cartera) || ', mayor ' || (SELECT saldo FROM mayor) AS detalle;

-- 10a) Every written-off account has its write-off voucher, and every recovery payment its recovery voucher.
SELECT '10a quebrantos sin póliza' AS comprobacion, count(*) AS fuera, count(*) || ' cuentas castigadas sin WRITE_OFF' AS detalle
  FROM credit_portfolio.credit_accounts a
 WHERE a.status = 'WRITTEN_OFF'
   AND NOT EXISTS (SELECT 1 FROM accounting.vouchers v WHERE v.credit_account_id = a.credit_account_id AND v.trigger_event = 'WRITE_OFF');

SELECT '10b recuperaciones sin póliza' AS comprobacion, count(*) AS fuera, count(*) || ' recuperaciones' AS detalle
  FROM credit_portfolio.balance_events be
 WHERE be.event_type = 'RECOVERY_PAYMENT'
   AND NOT EXISTS (SELECT 1 FROM accounting.vouchers v WHERE v.credit_account_id = be.credit_account_id AND v.trigger_event = 'RECOVERY_PAYMENT');

-- 10c) Opening fees deferred over the loan's life (IFRS 9): the 2150 balance is what the schedules still hold.
WITH mayor AS (
    SELECT coalesce(sum(amount) FILTER (WHERE credit_account = '2150'), 0)
         - coalesce(sum(amount) FILTER (WHERE debit_account = '2150'), 0) AS saldo
      FROM accounting.journal_entries),
     calendario AS (SELECT coalesce(sum(total - recognized) FILTER (WHERE status = 'ACTIVE'), 0) AS pendiente
                      FROM accounting.deferred_fees WHERE kind = 'FEE')
SELECT '10c comisiones por devengar: mayor 2150 vs calendarios' AS comprobacion,
       abs(round((SELECT saldo FROM mayor) - (SELECT pendiente FROM calendario), 2)) AS fuera,
       'mayor ' || (SELECT saldo FROM mayor) || ', calendarios ' || (SELECT pendiente FROM calendario) AS detalle;

-- 10d) Stage 3 interest not recognized: the memo (7401) equals what went to the allowance with the interest charges.
SELECT '10d interés de etapa 3 no reconocido: orden 7401 vs 1290' AS comprobacion,
       abs(round(coalesce(sum(amount) FILTER (WHERE debit_account = '7401'), 0)
               - coalesce(sum(amount) FILTER (WHERE credit_account = '1290'
                                                AND trigger_event IN ('CHARGE_ORDINARY_INTEREST', 'CHARGE_MORATORIUM_INTEREST')), 0), 2)) AS fuera,
       'orden ' || coalesce(sum(amount) FILTER (WHERE debit_account = '7401'), 0) AS detalle
  FROM accounting.journal_entries;

-- ── IFRS 9 reserves ─────────────────────────────────────────────────────────────────────────────

-- 10e) No account is incorporated against equity: every account is born in the platform and accounting books it
--      from its activation. A charge processed before the activation used to land in 3901 as "moratorios
--      incorporados", short of fee income by the same amount (E2E 3 Nov: 245 in two accounts).
SELECT '10e incorporaciones contra capital (3901)' AS comprobacion, count(*) AS fuera,
       'importe ' || coalesce(sum(amount), 0) AS detalle
  FROM accounting.journal_entries
 WHERE trigger_event = 'OPENING_BALANCE' OR '3901' IN (debit_account, credit_account);

-- 10f) Money moves in centavos: an entry with a fraction of a centavo cannot be settled by any payment
--      (the distributor's commission was basis × rate unrounded: 126.924).
SELECT '10f asientos con fracción de centavo' AS comprobacion, count(*) AS fuera,
       coalesce(string_agg(DISTINCT trigger_event, ', '), 'todos en centavos') AS detalle
  FROM accounting.journal_entries WHERE amount <> round(amount, 2);

-- 11) Each account's reserve in accounting is the one risk computed.
SELECT '11 reserva por cuenta: riesgo vs contabilidad' AS comprobacion,
       count(*) FILTER (WHERE abs(round(coalesce(r.provision_amount, 0), 2) - coalesce(p.last_booked_provision, 0)) > 0.01) AS fuera,
       count(*) || ' cuentas, riesgo ' || round(coalesce(sum(r.provision_amount), 0), 2) || ', contabilidad ' || coalesce(sum(p.last_booked_provision), 0) AS detalle
  FROM risk.risk_profiles r
  FULL OUTER JOIN accounting.provision_ledger p ON p.credit_account_id = r.credit_account_id;

-- 12) The 1290 balance is the sum of the reserves booked.
WITH mayor AS (
    SELECT coalesce(sum(CASE WHEN credit_account = '1290' THEN amount ELSE 0 END), 0)
         - coalesce(sum(CASE WHEN debit_account = '1290' THEN amount ELSE 0 END), 0) AS saldo
      FROM accounting.journal_entries)
SELECT '12 estimación preventiva: mayor 1290 vs reservas' AS comprobacion,
       abs(round((SELECT saldo FROM mayor) - (SELECT coalesce(sum(last_booked_provision), 0) FROM accounting.provision_ledger), 2)) AS fuera,
       'mayor ' || (SELECT saldo FROM mayor) || ', reservas ' || (SELECT coalesce(sum(last_booked_provision), 0) FROM accounting.provision_ledger) AS detalle;

-- 13) No reserve left on a settled or written-off account.
SELECT '13 reserva en cuentas cerradas' AS comprobacion, count(*) AS fuera,
       'importe ' || round(coalesce(sum(r.provision_amount), 0), 2) AS detalle
  FROM risk.risk_profiles r
  JOIN credit_portfolio.credit_accounts a ON a.credit_account_id = r.credit_account_id
 WHERE a.status IN ('SETTLED', 'WRITTEN_OFF') AND r.provision_amount > 0.005;

-- 14) Stage follows delinquency: more than 90 days past due is Stage 3, more than 30 at least Stage 2.
SELECT '14 etapa IFRS 9 vs días de atraso' AS comprobacion,
       count(*) FILTER (WHERE (r.days_delinquent > 90 AND r.ifrs9_stage <> 'STAGE_3')
                           OR (r.days_delinquent > 30 AND r.ifrs9_stage = 'STAGE_1')) AS fuera,
       count(*) FILTER (WHERE r.ifrs9_stage = 'STAGE_1') || ' en etapa 1, ' || count(*) FILTER (WHERE r.ifrs9_stage = 'STAGE_2') ||
       ' en 2, ' || count(*) FILTER (WHERE r.ifrs9_stage = 'STAGE_3') || ' en 3' AS detalle
  FROM risk.risk_profiles r WHERE r.status = 'ACTIVE';

-- 16) Every month that ended was closed by a reconciled close: the close checks the subledgers are complete and the
--     ledger equals them account by account before it publishes the month. A month before the last one still open,
--     or closed without a reconciled close, is out.
SELECT '16 meses terminados sin cierre conciliado' AS comprobacion, count(*) AS fuera,
       coalesce(string_agg(p.period || ' ' || CASE WHEN p.status = 'OPEN' THEN 'abierto'
                                                   ELSE coalesce(c.outcome, 'sin conciliación') END, ', ' ORDER BY p.period),
                'todos cerrados y conciliados') AS detalle
  FROM accounting.accounting_periods p
  LEFT JOIN accounting.period_closes c ON c.period = p.period
 WHERE p.period < (SELECT max(period) FROM accounting.accounting_periods)
   AND (p.status = 'OPEN' OR c.outcome IS DISTINCT FROM 'RECONCILED');

-- 17) Banks never ran negative at a month's end: the money lent came from somewhere the books recorded (funding).
WITH m AS (
    SELECT period, SUM(CASE WHEN debit_account = '1101' THEN amount ELSE 0 END)
                 - SUM(CASE WHEN credit_account = '1101' THEN amount ELSE 0 END) AS neto
      FROM accounting.journal_entries WHERE '1101' IN (debit_account, credit_account) GROUP BY period),
     s AS (SELECT period, SUM(neto) OVER (ORDER BY period) AS saldo FROM m)
SELECT '17 bancos 1101 negativo al cierre de un mes' AS comprobacion, count(*) FILTER (WHERE saldo < -0.005) AS fuera,
       coalesce(string_agg(period || ' ' || round(saldo, 2), ', ') FILTER (WHERE saldo < -0.005),
                'ningún mes en negativo') AS detalle
  FROM s;

-- 18) The funding in the ledger is treasury's: capital paid in (3101), lines drawn (2201) and their interest owed (2202).
WITH t AS (SELECT coalesce(sum(contributed), 0) AS capital, coalesce(sum(principal), 0) AS principal,
                  coalesce(sum(interest_payable), 0) AS interes FROM banking.funding_sources),
     g AS (SELECT coalesce(sum(amount) FILTER (WHERE credit_account = '3101'), 0) - coalesce(sum(amount) FILTER (WHERE debit_account = '3101'), 0) AS capital,
                  coalesce(sum(amount) FILTER (WHERE credit_account = '2201'), 0) - coalesce(sum(amount) FILTER (WHERE debit_account = '2201'), 0) AS principal,
                  coalesce(sum(amount) FILTER (WHERE credit_account = '2202'), 0) - coalesce(sum(amount) FILTER (WHERE debit_account = '2202'), 0) AS interes
             FROM accounting.journal_entries)
SELECT '18 fondeo: tesorería vs mayor 3101/2201/2202' AS comprobacion,
       abs(round(t.capital - g.capital, 2)) + abs(round(t.principal - g.principal, 2)) + abs(round(t.interes - g.interes, 2)) AS fuera,
       'capital ' || t.capital || '/' || g.capital || ', préstamos ' || t.principal || '/' || g.principal
           || ', intereses ' || t.interes || '/' || g.interes AS detalle
  FROM t, g;

-- 19) No message left in a dead-letter topic: a fact a consumer could not process is a fact the books, the portfolio
--     or the statements do not have (3 Nov: a funding line's opening fee went to the DLT and the run still passed).
SELECT '19 mensajes en DLT sin resolver' AS comprobacion, count(*) AS fuera,
       coalesce(string_agg(DISTINCT original_topic || ' (' || service || ')', ', '), 'ninguno') AS detalle
  FROM audit.failed_messages WHERE status = 'PENDING';

-- 20) No statement falls due on a non-business day (a weekend or a bank holiday of the calendar): the due date moves to
--     the next business day (closing, NonBusinessDayShift NEXT).
SELECT '20 estados con fecha límite en día inhábil' AS comprobacion, count(*) AS fuera,
       coalesce(string_agg(DISTINCT s.payment_due_date::text, ', '), 'ninguno') AS detalle
  FROM closing.cutoff_schedules s
 WHERE extract(isodow FROM s.payment_due_date) IN (6, 7)
    OR EXISTS (SELECT 1 FROM closing.calendar_days c
                WHERE c.calendar_code = 'MX' AND c.day = s.payment_due_date AND NOT c.is_business);

-- 24) What the ledger owes in commissions (2120) is what commission owes: the gross (commission and IVA) accrued and
--     not in a lot yet (a reversal is a negative record), plus the lots whose SPEI failed and wait for the next run.
WITH com AS (
    SELECT coalesce(sum(amount + iva_amount) FILTER (WHERE status = 'ACCRUED'), 0) AS abierto
      FROM commission.commission_records),
     lot AS (
    SELECT coalesce(sum(total_amount) FILTER (WHERE status = 'PENDING'), 0) AS pendiente
      FROM commission.liquidation_batches),
     mayor AS (
    SELECT coalesce(sum(amount) FILTER (WHERE credit_account = '2120'), 0)
         - coalesce(sum(amount) FILTER (WHERE debit_account = '2120'), 0) AS saldo
      FROM accounting.journal_entries)
SELECT '24 comisiones por pagar: mayor 2120 vs commission' AS comprobacion,
       abs(round((SELECT saldo FROM mayor) - (SELECT abierto FROM com) - (SELECT pendiente FROM lot), 2)) AS fuera,
       'mayor ' || round((SELECT saldo FROM mayor), 2) || ', devengado sin lote ' || round((SELECT abierto FROM com), 2)
           || ', lotes por pagar ' || round((SELECT pendiente FROM lot), 2) AS detalle;

-- 25) Every lot whose SPEI was requested has its order (the latest attempt) for its net; a paid lot's order was settled
--     by the bank, and a lot still in flight has no order that already ended (commission missed the outcome).
SELECT '25 lotes de comisiones vs órdenes SPEI' AS comprobacion,
       count(*) FILTER (WHERE o.amount IS NULL OR abs(o.amount - b.net_amount) > 0.005
                           OR (b.status = 'CONFIRMED' AND o.status <> 'SETTLED')
                           OR (b.status = 'SENT' AND o.status IN ('SETTLED', 'REJECTED', 'FAILED', 'RETURNED', 'CANCELLED'))) AS fuera,
       count(*) || ' lotes (' || count(*) FILTER (WHERE b.status = 'CONFIRMED') || ' pagados por '
           || coalesce(sum(b.net_amount) FILTER (WHERE b.status = 'CONFIRMED'), 0) || ' netos)' AS detalle
  FROM commission.liquidation_batches b
  LEFT JOIN LATERAL (SELECT d.amount, d.status FROM disbursement.disbursement_orders d
                      WHERE d.source_reference = 'LOTE:' || b.batch_id
                      ORDER BY d.created_at DESC LIMIT 1) o ON true
 WHERE b.status IN ('SENT', 'CONFIRMED');

-- 26) What was withheld from natural persons is what the ledger owes the SAT: ISR (2111) and IVA (2112).
WITH lotes AS (
    SELECT coalesce(sum(isr_withheld), 0) AS isr, coalesce(sum(iva_withheld), 0) AS iva
      FROM commission.liquidation_batches WHERE status IN ('SENT', 'CONFIRMED')),
     mayor AS (
    SELECT coalesce(sum(amount) FILTER (WHERE credit_account = '2111'), 0)
         - coalesce(sum(amount) FILTER (WHERE debit_account = '2111'), 0) AS isr,
           coalesce(sum(amount) FILTER (WHERE credit_account = '2112'), 0)
         - coalesce(sum(amount) FILTER (WHERE debit_account = '2112'), 0) AS iva
      FROM accounting.journal_entries)
SELECT '26 retenciones de comisiones: commission vs mayor 2111/2112' AS comprobacion,
       abs(round((SELECT isr FROM lotes) - (SELECT isr FROM mayor), 2))
           + abs(round((SELECT iva FROM lotes) - (SELECT iva FROM mayor), 2)) AS fuera,
       'ISR ' || (SELECT isr FROM lotes) || ' / ' || round((SELECT isr FROM mayor), 2) || ', IVA '
           || (SELECT iva FROM lotes) || ' / ' || round((SELECT iva FROM mayor), 2) AS detalle;

-- 27) The commissions' IVA: what the beneficiaries were paid is creditable (1140); what is still to pay, or was withheld
--     and is not paid to the SAT yet, is pending (1141).
WITH com AS (
    SELECT coalesce(sum(iva_amount) FILTER (WHERE status = 'ACCRUED'), 0) AS abierto
      FROM commission.commission_records),
     lot AS (
    SELECT coalesce(sum(iva_amount) FILTER (WHERE status = 'PENDING'), 0) AS pendiente,
           coalesce(sum(iva_amount - iva_withheld) FILTER (WHERE status IN ('SENT', 'CONFIRMED')), 0) AS pagado,
           coalesce(sum(iva_withheld) FILTER (WHERE status IN ('SENT', 'CONFIRMED')), 0) AS retenido
      FROM commission.liquidation_batches),
     mayor AS (
    SELECT coalesce(sum(amount) FILTER (WHERE debit_account = '1140'), 0)
         - coalesce(sum(amount) FILTER (WHERE credit_account = '1140'), 0) AS acreditable,
           coalesce(sum(amount) FILTER (WHERE debit_account = '1141'), 0)
         - coalesce(sum(amount) FILTER (WHERE credit_account = '1141'), 0) AS por_acreditar
      FROM accounting.journal_entries)
SELECT '27 IVA de comisiones: commission vs mayor 1140/1141' AS comprobacion,
       abs(round((SELECT acreditable FROM mayor) - (SELECT pagado FROM lot), 2))
           + abs(round((SELECT por_acreditar FROM mayor) - (SELECT abierto FROM com) - (SELECT pendiente FROM lot)
                       - (SELECT retenido FROM lot), 2)) AS fuera,
       'acreditable ' || round((SELECT acreditable FROM mayor), 2) || ' / ' || (SELECT pagado FROM lot)
           || ', por acreditar ' || round((SELECT por_acreditar FROM mayor), 2) || ' / '
           || ((SELECT abierto FROM com) + (SELECT pendiente FROM lot) + (SELECT retenido FROM lot)) AS detalle;

-- 28) Every entry of a loan carries the loan's product: the dimension of the trial balance by product.
SELECT '28 asientos de crédito sin producto' AS comprobacion,
       count(*) FILTER (WHERE credit_account_id IS NOT NULL AND product_type IS NULL) AS fuera,
       count(DISTINCT product_type) || ' productos en ' || count(*) || ' asientos' AS detalle
  FROM accounting.journal_entries;

-- 29) In a closed month no bank side is left without its product when its item is known: the close's sweep attributed it
--     (an order through its link to the loan or the lot, a received credit through the one product it was applied to).
WITH pendientes AS (
    SELECT v.voucher_id, v.trigger_event, v.clearing_ref FROM accounting.vouchers v
      JOIN accounting.accounting_periods p ON p.period = v.period AND p.status = 'CLOSED'
     WHERE v.product_type IS NULL AND v.trigger_event LIKE 'TREASURY_%'),
     conocidos AS (
    SELECT x.voucher_id FROM pendientes x
      JOIN accounting.payout_links l ON l.payment_request_id::text = x.clearing_ref
      LEFT JOIN accounting.account_balance_shadows s ON s.credit_account_id = l.credit_account_id
     WHERE x.trigger_event = 'TREASURY_STP_ORDER'
       AND (s.product_type IS NOT NULL OR EXISTS (SELECT 1 FROM accounting.commission_facts c
                                                   WHERE l.item_ref = 'LOTE:' || c.batch_id AND c.product_type IS NOT NULL))
    UNION ALL
    SELECT x.voucher_id FROM pendientes x
     WHERE x.trigger_event IN ('TREASURY_SPEI_CREDIT', 'TREASURY_SPEI_CREDIT_RETURNED')
       AND (SELECT count(DISTINCT a.product_type) FROM accounting.vouchers a
             WHERE a.clearing_ref = x.clearing_ref AND a.credit_account_id IS NOT NULL AND a.product_type IS NOT NULL) = 1)
SELECT '29 lado del banco sin producto en meses cerrados' AS comprobacion,
       (SELECT count(*) FROM conocidos) AS fuera,
       (SELECT count(*) FROM pendientes) || ' de la institución (sin partida de un solo producto)' AS detalle;

-- 30) Every order the connector finished, the disbursement heard: no payout waits for an answer STP already gave. The
--     demo of 2026-11-04 had two: a -30 exhausted and published as retryable (D58), and a timeout failed in silence (D59).
SELECT '30 órdenes terminadas en STP que la dispersión no oyó' AS comprobacion,
       count(*) FILTER (WHERE d.status NOT IN ('SETTLED', 'REJECTED', 'RETURNED', 'FAILED', 'CANCELLED')) AS fuera,
       count(*) || ' órdenes terminadas en STP (' || count(*) FILTER (WHERE o.status <> 'SETTLED') || ' sin liquidar)'
           AS detalle
  FROM stp.payment_orders o
  JOIN disbursement.disbursement_orders d ON d.disbursement_id = o.payment_request_id
 WHERE o.status IN ('SETTLED', 'REJECTED', 'RETURNED', 'FAILED', 'CANCELLED');

-- 21) No loan installment falls due on a non-business day: the plan is dated on business days (the «following»
--     convention), and the client who pays on the next business day is not late.
SELECT '21 cuotas de préstamo que vencen en día inhábil' AS comprobacion, count(*) AS fuera,
       coalesce(string_agg(DISTINCT i.due_date::text, ', '), 'ninguna') AS detalle
  FROM credit_portfolio.installments i
  JOIN credit_portfolio.credit_accounts a ON a.credit_account_id = i.schedule_id AND a.product_behavior <> 'REVOLVING'
 WHERE i.status <> 'REPLACED'
   AND (extract(isodow FROM i.due_date) IN (6, 7)
        OR EXISTS (SELECT 1 FROM closing.calendar_days c
                    WHERE c.calendar_code = 'MX' AND c.day = i.due_date AND NOT c.is_business));

-- 22) Banks (1101) equal treasury's cash book month by month: the ledger books the bank from the portfolio's and the
--     other services' facts, treasury keeps every movement of the account from its own (STP orders, funding, clients'
--     payments, returns, commissions paid). A payment the portfolio rejected came in and went back: left out.
WITH rejected AS (
    SELECT substr(source_event_id, length('PAYMENT-REJECTED:') + 1) AS payment
      FROM accounting.treasury_cash_book WHERE source_event_id LIKE 'PAYMENT-REJECTED:%'),
     book AS (
    SELECT period, SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS neto
      FROM accounting.treasury_cash_book
     WHERE source_event_id NOT LIKE 'PAYMENT-REJECTED:%'
       AND NOT (movement_type = 'CLIENT_PAYMENT' AND substr(source_event_id, length('PAYMENT:') + 1) IN (SELECT payment FROM rejected))
     GROUP BY period),
     ledger AS (
    SELECT period, SUM(CASE WHEN debit_account = '1101' THEN amount ELSE -amount END) AS neto
      FROM accounting.journal_entries WHERE '1101' IN (debit_account, credit_account) AND period ~ '^[0-9]{4}(0[1-9]|1[0-2])$'
     GROUP BY period),
     m AS (SELECT coalesce(b.period, l.period) AS period, coalesce(b.neto, 0) AS tesoreria, coalesce(l.neto, 0) AS mayor
             FROM book b FULL JOIN ledger l ON l.period = b.period)
SELECT '22 bancos 1101 vs libro de caja de tesorería' AS comprobacion,
       count(*) FILTER (WHERE abs(tesoreria - mayor) > 0.005) AS fuera,
       coalesce(string_agg(period || ' tesorería ' || round(tesoreria, 2) || ' / mayor ' || round(mayor, 2), ', ' ORDER BY period)
                    FILTER (WHERE abs(tesoreria - mayor) > 0.005),
                count(*) || ' meses iguales') AS detalle
  FROM m;

-- 23) Every movement of treasury's cash book reached accounting (banking.cash-movement-recorded): what accounting
--     reconciles 1101 against is the whole book, not part of it.
SELECT '23 movimientos de tesorería sin llegar a contabilidad' AS comprobacion, count(*) AS fuera,
       coalesce(string_agg(DISTINCT i.movement_type, ', '), 'todos recibidos') AS detalle
  FROM banking.internal_movements i
 WHERE NOT EXISTS (SELECT 1 FROM accounting.treasury_cash_book c WHERE c.movement_id = i.internal_movement_id);

-- 15) Every live account has a risk profile with a policy behind it: an exposure without a reserve rate is a reserve of zero.
SELECT '15 cuentas vivas sin reserva calculada' AS comprobacion, count(*) AS fuera,
       coalesce(string_agg(DISTINCT a.product_type, ', '), 'todas con perfil') AS detalle
  FROM credit_portfolio.credit_accounts a
  LEFT JOIN risk.risk_profiles r ON r.credit_account_id = a.credit_account_id
 WHERE a.status IN ('ACTIVE', 'SUSPENDED', 'RESTRUCTURED')
   AND (a.principal_balance + a.accrued_interest_balance + a.iva_balance + a.penalty_balance) > 0
   AND (r.credit_account_id IS NULL OR r.expected_loss_rate = 0);

-- 04f) The moratorium, day by day: on the days an account owes a statement past its grace it is charged once, the
--      overdue PRINCIPAL of the day snapshot × rate / 360 (never the interest, IVA or moratorium the statement carried),
--      and on no other day. The rate is the charge's own (a restructure may change it); the base is the snapshot's.
WITH mora AS (
    SELECT credit_account_id, accrual_date, sum(amount) AS amount, max(rate) AS rate, count(*) AS n
      FROM charges.charge_records WHERE charge_type = 'MORATORIUM_INTEREST' AND status = 'APPLIED'
     GROUP BY 1, 2)
SELECT '04f moratorio diario vs capital vencido de la foto' AS comprobacion,
       count(*) FILTER (WHERE abs(coalesce(m.amount, 0)
                            - CASE WHEN f.overdue_principal > 0 AND f.oldest_due_date + s.grace_period_days <= f.business_date
                                   THEN round(f.overdue_principal * coalesce(m.rate, s.moratorium_rate) / 360, 2) ELSE 0 END) > 0.01
                           OR coalesce(m.n, 0) > 1) AS fuera,
       count(*) FILTER (WHERE m.amount > 0) || ' días con moratorio, ' || coalesce(sum(m.amount), 0) || ' en total' AS detalle
  FROM credit_portfolio.account_day_snapshots f
  JOIN charges.accrual_schedules s ON s.credit_account_id = f.credit_account_id
  LEFT JOIN mora m ON m.credit_account_id = f.credit_account_id AND m.accrual_date = f.business_date
 WHERE f.account_status = 'ACTIVE';

-- 31) A distributor line does not place while a statement of hers is unpaid past its due date: no placement was
--     authorized on a day the previous day's snapshot had her late, unless she paid that morning before placing.
SELECT '31 colocaciones autorizadas con un estado vencido sin pagar' AS comprobacion, count(*) AS fuera,
       coalesce(string_agg(a.contract_number || ' ' || (d.created_at AT TIME ZONE 'America/Mexico_City')::date, ', '), 'ninguna') AS detalle
  FROM credit_portfolio.dispositions d
  JOIN credit_portfolio.credit_accounts a ON a.credit_account_id = d.credit_account_id AND a.product_type = 'DISTRIBUTOR_LINE'
  JOIN credit_portfolio.account_day_snapshots f ON f.credit_account_id = d.credit_account_id
   AND f.business_date = (d.created_at AT TIME ZONE 'America/Mexico_City')::date - 1
 WHERE d.status <> 'FAILED' AND f.days_delinquent > 0
   AND NOT EXISTS (SELECT 1 FROM credit_portfolio.balance_events e
                    WHERE e.credit_account_id = d.credit_account_id
                      AND e.event_type IN ('PAYMENT_APPLIED', 'COMMISSION_NETTED', 'AGREEMENT_QUITA_PARCIAL')
                      AND e.value_date = (d.created_at AT TIME ZONE 'America/Mexico_City')::date AND e.applied_at < d.created_at);

-- 32) The distributor's commission on every placement plan: the product's share of each installment the client pays,
--     adding up exactly, and never ahead of the interest paid so far (it comes out of the interest, not the capital).
WITH q AS (
    SELECT i.schedule_id, i.installment_number, i.total_amount, i.interest_amount, i.scheduled_commission,
           (v.distributor_commission->>'rate')::numeric AS tasa,
           sum(i.scheduled_commission) OVER (PARTITION BY i.schedule_id ORDER BY i.installment_number) AS com_acum,
           sum(i.interest_amount) OVER (PARTITION BY i.schedule_id ORDER BY i.installment_number) AS int_acum
      FROM credit_portfolio.installments i
      JOIN credit_portfolio.dispositions d ON d.disposition_id = i.schedule_id
      JOIN credit_portfolio.credit_accounts a ON a.credit_account_id = d.credit_account_id
      JOIN credit_portfolio.product_config_versions v ON v.product_code = a.product_code AND v.product_version = a.product_version
     WHERE v.distributor_commission IS NOT NULL AND d.status <> 'FAILED' AND i.status NOT IN ('REPLACED', 'CANCELLED')),
     por_plan AS (
    SELECT schedule_id, bool_or(com_acum > int_acum + 0.005) AS por_delante, bool_or(scheduled_commission IS NULL) AS sin_comision,
           abs(sum(scheduled_commission) - least(sum(round(total_amount * tasa, 2)), sum(interest_amount))) > 0.01 AS suma_mal
      FROM q GROUP BY schedule_id)
SELECT '32 comisión de la distribuidora en los planes: plana, exacta y nunca por delante del interés' AS comprobacion,
       count(*) FILTER (WHERE por_delante OR sin_comision OR suma_mal) AS fuera,
       count(*) || ' planes' AS detalle
  FROM por_plan;

-- 33) What a distributor's commission settled (COMMISSION_NETTED in the portfolio's ledger) is what commission recorded
--     as compensated, to the cent, and what accounting booked against 2120: three books, one figure.
WITH cartera AS (
    SELECT coalesce(sum(CASE WHEN event_type = 'COMMISSION_NETTED' THEN -delta_amount ELSE -abs(delta_amount) END), 0) AS neteado
      FROM credit_portfolio.balance_events WHERE event_type IN ('COMMISSION_NETTED', 'COMMISSION_NETTED_REVERSED')),
     commission AS (
    SELECT coalesce(sum(amount), 0) AS neteado FROM commission.commission_records WHERE status = 'COMPENSATED'),
     mayor AS (
    SELECT coalesce(sum(amount) FILTER (WHERE debit_account = '2120' AND trigger_event = 'COMMISSION_NETTED'), 0)
         - coalesce(sum(amount) FILTER (WHERE credit_account = '2120' AND trigger_event = 'COMMISSION_NETTED_REVERSED'), 0) AS neteado
      FROM accounting.journal_entries)
SELECT '33 comisión neteada: cartera vs commission vs mayor 2120' AS comprobacion,
       abs(round((SELECT neteado FROM cartera) - (SELECT neteado FROM commission), 2))
         + abs(round((SELECT neteado FROM cartera) - (SELECT neteado FROM mayor), 2)) AS fuera,
       'cartera ' || round((SELECT neteado FROM cartera), 2) || ', commission ' || round((SELECT neteado FROM commission), 2)
         || ', mayor ' || round((SELECT neteado FROM mayor), 2) AS detalle;
