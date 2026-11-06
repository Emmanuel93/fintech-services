--liquibase formatted sql
--changeset charges:016-iva-del-periodo
--comment The IVA the plans set for each day's periods: what a per-period account is charged as IVA.

-- A per-period account (a distributor line) owes each placement's installment as its client pays it: interest and the
-- IVA rounded installment by installment. Sixteen percent of the day's total interest, rounded once, differed by a cent
-- or two from what the clients paid, and the statement's rows did not add up to its total.
ALTER TABLE charges.account_day_snapshots
    ADD COLUMN IF NOT EXISTS scheduled_tax NUMERIC(19, 2);

ALTER TABLE charges.accrual_units
    ADD COLUMN IF NOT EXISTS basis_scheduled_tax NUMERIC(19, 2);
