--liquibase formatted sql
--changeset credit-portfolio:031-foto-diaria
--comment Per-account day snapshot: how each account stood at the end of D. Charges accrues and closing cuts from it.

-- Log: one snapshot per day, with its gates and control totals.
CREATE TABLE IF NOT EXISTS credit_portfolio.day_snapshots (
    business_date       DATE           PRIMARY KEY,
    status              TEXT           NOT NULL CHECK (status IN ('WAITING', 'RUNNING', 'DONE', 'FAILED')),
    triggered_by        TEXT,
    accounts            INTEGER,
    delinquent_accounts INTEGER,
    principal_total     NUMERIC(20, 4),
    overdue_total       NUMERIC(20, 4),
    waiting_for         TEXT,
    last_error          TEXT,
    attempts            INTEGER        NOT NULL DEFAULT 0,
    started_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

-- The account as it stood when D ended (23:59:59 in the business zone). Anything applied later with a
-- later value date is subtracted, so the snapshot is the same whether taken at 00:05 or at 03:00.
-- It is the record the three services share: charges accrues D on `principal` and decides the moratorium
-- from `overdue_principal` and `oldest_due_date`; closing seals `days_delinquent` and checks `principal`.
CREATE TABLE IF NOT EXISTS credit_portfolio.account_day_snapshots (
    business_date        DATE           NOT NULL,
    credit_account_id    UUID           NOT NULL,
    account_status       TEXT           NOT NULL,
    principal            NUMERIC(15, 2) NOT NULL,
    accrued_interest     NUMERIC(15, 2) NOT NULL,
    iva                  NUMERIC(15, 2) NOT NULL,
    penalty              NUMERIC(15, 2) NOT NULL,
    total_debt           NUMERIC(15, 2) NOT NULL,
    overdue_principal    NUMERIC(15, 2) NOT NULL,
    overdue_installments INTEGER        NOT NULL,
    oldest_due_date      DATE,
    days_delinquent      INTEGER        NOT NULL,
    balance_version      BIGINT         NOT NULL,
    later_movements      INTEGER        NOT NULL DEFAULT 0,
    taken_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (business_date, credit_account_id)
);
-- One account's history, day by day: the step-by-step view behind a statement.
CREATE INDEX IF NOT EXISTS idx_account_day_snapshots_account
    ON credit_portfolio.account_day_snapshots (credit_account_id, business_date);

-- Unpaid installments by schedule and due date: what the snapshot sums as overdue principal.
CREATE INDEX IF NOT EXISTS idx_installments_unpaid_schedule_due
    ON credit_portfolio.installments (schedule_id, due_date)
    WHERE status IN ('PENDING', 'PARTIAL', 'OVERDUE');
