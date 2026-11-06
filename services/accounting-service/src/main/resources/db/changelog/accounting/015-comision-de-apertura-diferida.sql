--liquibase formatted sql
--changeset accounting:015-comision-de-apertura-diferida
--comment The opening fee is deferred over the life of the loan (IFRS 9: a fee integral to the effective interest rate).

-- Until now the opening fee went to revenue (4103) the day of the disbursement. Under IFRS 9 it is part of the
-- effective interest rate: it is recognized over the life of the loan. It is charged to the receivable as before and
-- credited to 2150; every month the part of the months elapsed goes to 4103, and whatever is left when the loan is
-- settled or written off is recognized then.
INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('2150', 'Comisiones de apertura por devengar', 'LIABILITY')
ON CONFLICT (code) DO NOTHING;

UPDATE accounting.posting_rules
   SET credit_account = '2150',
       description = 'Comisión de apertura — se difiere en la vida del crédito'
 WHERE trigger_event = 'CHARGE_OPENING_FEE';

-- The loan's life in months, learned at activation: what the deferral is spread over.
ALTER TABLE accounting.account_balance_shadows
    ADD COLUMN IF NOT EXISTS term_months INT;

CREATE TABLE IF NOT EXISTS accounting.deferred_fees (
    deferred_fee_id    UUID          NOT NULL,
    source_event_id    VARCHAR(120)  NOT NULL,
    credit_account_id  UUID          NOT NULL,
    obligor_party_id   UUID,
    org_unit_code      VARCHAR(40),
    total              NUMERIC(19,2) NOT NULL,
    recognized         NUMERIC(19,2) NOT NULL DEFAULT 0,
    months             INT           NOT NULL,
    first_period       VARCHAR(6)    NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_deferred_fees PRIMARY KEY (deferred_fee_id),
    CONSTRAINT uq_deferred_fees_source UNIQUE (source_event_id),
    CONSTRAINT chk_deferred_fees_status CHECK (status IN ('ACTIVE', 'DONE')),
    CONSTRAINT chk_deferred_fees_recognized CHECK (recognized >= 0 AND recognized <= total),
    CONSTRAINT chk_deferred_fees_months CHECK (months > 0)
);

CREATE INDEX IF NOT EXISTS idx_deferred_fees_account ON accounting.deferred_fees (credit_account_id) WHERE status = 'ACTIVE';
