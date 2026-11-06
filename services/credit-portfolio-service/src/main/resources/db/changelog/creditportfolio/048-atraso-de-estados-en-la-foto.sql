--liquibase formatted sql
--changeset credit-portfolio:048-atraso-de-estados-en-la-foto
--comment What the statements past due still miss, in full, on the day snapshot of a statement-billed account.

-- The moratorium of a statement-billed account runs on the principal its unpaid statements billed (overdue_principal),
-- never on the interest, the IVA or the moratorium they carried. What the holder sees as arrears on her next statement
-- is the whole of what the past-due statements asked and she has not paid. Both are kept: the day snapshot now carries
-- that whole too, so the statement and the snapshot of its cutoff can be reconciled figure against figure (06b).
ALTER TABLE credit_portfolio.account_day_snapshots
    ADD COLUMN overdue_statements_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN credit_portfolio.account_day_snapshots.overdue_statements_amount IS
    'Lo que los estados de cuenta vencidos al cierre del día todavía piden, completo (capital, interés, IVA y moratorio facturados); overdue_principal es sólo su capital';
