--liquibase formatted sql
--changeset credit-portfolio:037-interes-del-periodo
--comment The interest the plans set for the periods that fall due each day, in the day snapshot.

-- A product that charges interest per period (a distributor line: each placement has its own amortization plan) is
-- charged, the day each period falls due, the interest that period's installment sets. The plans live here, so the
-- snapshot says it: charges reads it with the rest of the day's facts and decides by the product's interest mode.
ALTER TABLE credit_portfolio.account_day_snapshots
    ADD COLUMN IF NOT EXISTS scheduled_interest NUMERIC(19, 2);
