--liquibase formatted sql
--changeset credit-portfolio:043-exigido-del-dia-sin-pagar
--comment What a statement-billed account's statements asked to pay on the day itself and the day's end left unpaid.

-- One due on D is not late on D: the snapshot of D does not count it as overdue (it is from D+1). A statement cut on D
-- is issued once D is over, so what the previous statement asked to pay by D and was not paid is arrears in it. A
-- distributor line's statement falls due on the next one's cutoff, so the two figures differ every time a distributor
-- misses a due date. The snapshot carries that amount apart: the statement's arrears are the snapshot's overdue plus it.
ALTER TABLE credit_portfolio.account_day_snapshots
    ADD COLUMN statement_due_unpaid NUMERIC(19, 2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN credit_portfolio.account_day_snapshots.statement_due_unpaid IS
    'Lo que los estados de cuenta pedían pagar a más tardar este día y quedó sin pagar al cerrarlo (productos facturados por estado de cuenta): es atraso desde el día siguiente.';
