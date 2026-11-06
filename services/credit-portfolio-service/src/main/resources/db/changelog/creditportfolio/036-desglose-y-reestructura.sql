--liquibase formatted sql
--changeset credit-portfolio:036-desglose-y-reestructura
--comment Each ledger entry keeps what it moved in each bucket; a restructure replaces the unpaid installments.

-- What the entry moved in principal, interest, IVA and penalty (signed). A returned payment restores exactly
-- what its payment had covered; before, the whole amount went back as penalty, and the return was dropped
-- anyway because it carried the payment's own id (2 Nov, E06). NULL in entries written before this column.
ALTER TABLE credit_portfolio.balance_events
    ADD COLUMN IF NOT EXISTS principal_delta NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS interest_delta  NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS iva_delta       NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS penalty_delta   NUMERIC(19, 2);

-- REPLACED: an installment a restructure superseded. It stays for the record, owes nothing, and no aging,
-- snapshot or statement counts it.
ALTER TABLE credit_portfolio.installments DROP CONSTRAINT IF EXISTS ck_installment_status;
ALTER TABLE credit_portfolio.installments
    ADD CONSTRAINT ck_installment_status CHECK (status IN ('PENDING', 'PAID', 'OVERDUE', 'PARTIAL', 'REPLACED'));
