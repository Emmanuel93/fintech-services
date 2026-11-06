--liquibase formatted sql
--changeset accounting:016-cierre-conciliado
--comment The month-end close reconciles before it publishes: subledger complete, ECL measured, GL equal to the subledgers.

-- The days the portfolio closed (credit-portfolio.day-closed): the subledger holds every fact up to that day.
-- A month is not closed before its last day is.
CREATE TABLE IF NOT EXISTS accounting.subledger_day_closes (
    business_date  DATE         NOT NULL,
    accounts       INT,
    received_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_subledger_day_closes PRIMARY KEY (business_date)
);

-- Accounts a day closed without (charges held them out of the day's accrual): the day is complete once each one is
-- released (credit-portfolio.account-day-closed).
CREATE TABLE IF NOT EXISTS accounting.subledger_held_accounts (
    business_date      DATE         NOT NULL,
    credit_account_id  UUID         NOT NULL,
    released_at        TIMESTAMPTZ,
    CONSTRAINT pk_subledger_held_accounts PRIMARY KEY (business_date, credit_account_id)
);

-- When risk measured each account's expected credit loss (its calculatedAt), not when accounting booked it:
-- IFRS 9 measures the allowance at the reporting date, and the close checks it was.
ALTER TABLE accounting.provision_ledger ADD COLUMN IF NOT EXISTS measured_at TIMESTAMPTZ;

-- Each close of a month: the reconciliation it ran and, when it reconciled, the trial balance it published.
-- A close that did not reconcile is kept too (outcome DIFFERENCES): it says why the month is still open.
CREATE TABLE IF NOT EXISTS accounting.period_closes (
    period         VARCHAR(6)   NOT NULL,
    outcome        VARCHAR(12)  NOT NULL,
    run_at         TIMESTAMPTZ  NOT NULL,
    attempts       INT          NOT NULL DEFAULT 1,
    checks         JSONB        NOT NULL,
    trial_balance  JSONB,
    CONSTRAINT pk_period_closes PRIMARY KEY (period),
    CONSTRAINT chk_period_closes_outcome CHECK (outcome IN ('RECONCILED', 'DIFFERENCES'))
);
