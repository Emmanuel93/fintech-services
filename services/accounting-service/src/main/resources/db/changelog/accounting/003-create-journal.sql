--liquibase formatted sql
--changeset accounting:003-create-journal author:system

CREATE TABLE accounting.journal_entries (
    entry_id          UUID          NOT NULL DEFAULT gen_random_uuid(),
    source_event_id   VARCHAR(120)  NOT NULL,
    trigger_event     VARCHAR(60)   NOT NULL,
    credit_account_id UUID,
    obligor_party_id  UUID,
    debit_account     VARCHAR(10)   NOT NULL,
    credit_account    VARCHAR(10)   NOT NULL,
    amount            NUMERIC(19,4) NOT NULL,
    currency          VARCHAR(3)    NOT NULL DEFAULT 'MXN',
    description       VARCHAR(200),
    posting_date      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    period            VARCHAR(6)    NOT NULL,   -- YYYYMM
    reversal_ref      UUID,

    CONSTRAINT pk_journal_entries PRIMARY KEY (entry_id),
    -- GL: idempotencia — un asiento por (evento fuente, línea débito/crédito)
    CONSTRAINT uq_journal_entries_source UNIQUE (source_event_id, debit_account, credit_account),
    CONSTRAINT chk_journal_entries_amount CHECK (amount >= 0),
    CONSTRAINT fk_journal_entries_debit  FOREIGN KEY (debit_account)  REFERENCES accounting.ledger_accounts (code),
    CONSTRAINT fk_journal_entries_credit FOREIGN KEY (credit_account) REFERENCES accounting.ledger_accounts (code)
);

CREATE INDEX idx_journal_entries_account ON accounting.journal_entries (credit_account_id);
CREATE INDEX idx_journal_entries_party   ON accounting.journal_entries (obligor_party_id);
CREATE INDEX idx_journal_entries_period  ON accounting.journal_entries (period);
CREATE INDEX idx_journal_entries_ledger  ON accounting.journal_entries (period, debit_account, credit_account);

CREATE TABLE accounting.accounting_periods (
    period    VARCHAR(6)  NOT NULL,
    status    VARCHAR(8)  NOT NULL DEFAULT 'OPEN',
    closed_at TIMESTAMPTZ,
    CONSTRAINT pk_accounting_periods PRIMARY KEY (period),
    CONSTRAINT chk_accounting_periods_status CHECK (status IN ('OPEN','CLOSED'))
);
