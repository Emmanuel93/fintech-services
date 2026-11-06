--liquibase formatted sql
--changeset credit-portfolio:033-foto-con-libro
--comment The day snapshot carries the ledger total of each account and the gap against its balances: the daily integrity check.

-- Subledger integrity, checked every night: the sum of balance_events must equal the balances the
-- account carries. `ledger_gap` is ledger minus balances; anything but zero is a movement that touched
-- the balances without an entry, or an entry whose balance update was lost (both happened before 2026-11-02).
ALTER TABLE credit_portfolio.account_day_snapshots
    ADD COLUMN IF NOT EXISTS ledger_total NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS ledger_gap   NUMERIC(15, 2);

ALTER TABLE credit_portfolio.day_snapshots
    ADD COLUMN IF NOT EXISTS ledger_gaps INTEGER;
