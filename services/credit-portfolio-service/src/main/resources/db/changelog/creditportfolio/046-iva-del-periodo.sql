--liquibase formatted sql
--changeset credit-portfolio:046-iva-del-periodo
--comment The IVA the plans set for the periods that fall due each day, next to their interest, in the day snapshot.

-- A per-period product is charged the interest each placement's installment sets; each client pays that installment's
-- IVA, rounded on its own. Charging 16 % of the day's total interest rounded once left the line a cent or two away from
-- what its clients paid (90.17 against 20.02 + 6.81 + 58.11 + 5.24 = 90.18), so the statement's rows did not add up to
-- its total. The snapshot carries the plans' IVA and charges charges exactly that.
ALTER TABLE credit_portfolio.account_day_snapshots
    ADD COLUMN IF NOT EXISTS scheduled_tax NUMERIC(19, 2);
