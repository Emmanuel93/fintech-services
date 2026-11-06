--liquibase formatted sql
--changeset commission:013-comision-neteada
--comment The distributor's commission netted against her statement: settled from birth (COMPENSATED), with the statement it was netted against and how late the payment landed.

-- The market's rule for a distributor line: her commission is a flat share of what her clients pay each statement,
-- deducted from what she delivers if she delivers by the due date, less each day after, nothing past the third. The
-- portfolio settles it against the statement (COMMISSION_NETTED, next to her cash) and this service records it as it
-- was settled: it never waits for a lot, carries no IVA of its own and is undone only when its payment comes back.
ALTER TABLE commission.commission_records DROP CONSTRAINT IF EXISTS chk_commission_records_status;
ALTER TABLE commission.commission_records ADD CONSTRAINT chk_commission_records_status
    CHECK (status IN ('ACCRUED', 'LIQUIDATED', 'REVERSED', 'COMPENSATED'));

ALTER TABLE commission.commission_records
    ADD COLUMN IF NOT EXISTS settlement   VARCHAR(12),
    ADD COLUMN IF NOT EXISTS cycle_number INTEGER,
    ADD COLUMN IF NOT EXISTS due_date     DATE,
    ADD COLUMN IF NOT EXISTS days_late    INTEGER;

CREATE INDEX IF NOT EXISTS idx_commission_records_payment_ref ON commission.commission_records (payment_ref);
