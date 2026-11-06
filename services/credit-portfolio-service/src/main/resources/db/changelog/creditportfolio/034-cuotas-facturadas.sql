--liquibase formatted sql
--changeset credit-portfolio:034-cuotas-facturadas
--comment Installments of statement-billed products remember which statement billed them.

-- A line, a card or a distributor line is collected by statement, not by installment date. Each pending
-- installment of its plans (placements, deferred purchases, amortized purchases) is billed once, by the
-- first statement whose payment due date covers it, and from then on it is due on that date: the holder
-- is delinquent when the statement is not paid, never before. `billed_cycle` is the statement's cycle;
-- the original date stays in `original_due_date`. Unbilled installments of these products are never overdue.
ALTER TABLE credit_portfolio.installments
    ADD COLUMN IF NOT EXISTS billed_cycle INTEGER;

-- Cycle installments that already exist were billed by their own statement: their number is the cycle.
UPDATE credit_portfolio.installments i
   SET billed_cycle = i.installment_number
  FROM credit_portfolio.credit_accounts a
 WHERE a.credit_account_id = i.schedule_id
   AND a.product_behavior = 'REVOLVING'
   AND i.billed_cycle IS NULL;
