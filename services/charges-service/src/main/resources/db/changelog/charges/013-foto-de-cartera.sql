--liquibase formatted sql
--changeset charges:013-foto-de-cartera
--comment Charges accrues each day on the portfolio's per-account day snapshot, not on the live balance.

-- Local copy of the portfolio's snapshot of D, pulled before the day is planned. Only what the accrual
-- needs: the principal (ordinary basis), the overdue principal and the oldest due date (moratorium).
CREATE TABLE IF NOT EXISTS charges.account_day_snapshots (
    business_date      DATE           NOT NULL,
    credit_account_id  UUID           NOT NULL,
    principal          NUMERIC(19, 2) NOT NULL,
    overdue_principal  NUMERIC(19, 2) NOT NULL,
    oldest_due_date    DATE,
    days_delinquent    INTEGER        NOT NULL,
    balance_version    BIGINT         NOT NULL,
    taken_at           TIMESTAMPTZ    NOT NULL,
    received_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (business_date, credit_account_id)
);

-- The basis each unit accrued on, frozen when the day was planned. With it a charge is traceable:
-- basis_principal × rate / 360 = the day's ordinary interest, whatever the balance is today.
ALTER TABLE charges.accrual_units
    ADD COLUMN IF NOT EXISTS basis_principal         NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS basis_overdue_principal NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS basis_oldest_due_date   DATE,
    ADD COLUMN IF NOT EXISTS basis_version           BIGINT;
