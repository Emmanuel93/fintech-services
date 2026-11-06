--liquibase formatted sql
--changeset charges:015-interes-por-periodo
--comment Ordinary interest per period: the account's interest mode, and the interest the plans set for each day.

-- How each account accrues its ordinary interest, pinned at activation from the product's billing cycle: DAILY (rate /
-- 360 on the day's principal) or PER_PERIOD (the interest each plan sets for its period, charged the day it falls
-- due). Accounts opened before the mode existed were opened under daily accrual and keep it.
ALTER TABLE charges.accrual_schedules
    ADD COLUMN IF NOT EXISTS interest_accrual VARCHAR(12) NOT NULL DEFAULT 'DAILY';

-- What the portfolio's snapshot says the plans set for the periods falling due that day, and what each unit charged
-- on, so a per-period charge keeps its basis for the audit trail like a daily one keeps its principal.
ALTER TABLE charges.account_day_snapshots
    ADD COLUMN IF NOT EXISTS scheduled_interest NUMERIC(19, 2);

ALTER TABLE charges.accrual_units
    ADD COLUMN IF NOT EXISTS basis_scheduled_interest NUMERIC(19, 2);
