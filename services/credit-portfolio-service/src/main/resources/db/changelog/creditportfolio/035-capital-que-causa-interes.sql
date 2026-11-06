--liquibase formatted sql
--changeset credit-portfolio:035-capital-que-causa-interes
--comment The snapshot carries the principal that bears the line's ordinary interest: an interest-free deferred purchase does not.

-- principal minus the installments of interest-free deferred purchases (meses sin intereses) not yet due.
-- Charges accrues the ordinary interest of D on this column. NULL in snapshots taken before it existed.
ALTER TABLE credit_portfolio.account_day_snapshots
    ADD COLUMN IF NOT EXISTS interest_bearing_principal NUMERIC(19, 2);
