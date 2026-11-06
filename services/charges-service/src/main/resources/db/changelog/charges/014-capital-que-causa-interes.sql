--liquibase formatted sql
--changeset charges:014-capital-que-causa-interes
--comment The ordinary interest basis is the snapshot's interest-bearing principal, not the whole principal.

-- The portfolio's principal minus the installments of interest-free deferred purchases not yet due. A purchase
-- deferred at meses sin intereses does not bear the line's interest. NULL in copies made before the column
-- existed: the plan then falls back to the principal.
ALTER TABLE charges.account_day_snapshots
    ADD COLUMN IF NOT EXISTS interest_bearing_principal NUMERIC(19, 2);
