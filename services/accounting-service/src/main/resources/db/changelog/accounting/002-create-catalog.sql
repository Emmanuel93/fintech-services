--liquibase formatted sql
--changeset accounting:002-create-catalog author:system

-- Catálogo de cuentas contables (mayor)
CREATE TABLE accounting.ledger_accounts (
    code       VARCHAR(10)  NOT NULL,
    name       VARCHAR(120) NOT NULL,
    type       VARCHAR(12)  NOT NULL,
    CONSTRAINT pk_ledger_accounts PRIMARY KEY (code),
    CONSTRAINT chk_ledger_accounts_type CHECK (type IN ('ASSET','LIABILITY','INCOME','EXPENSE','EQUITY'))
);

-- Reglas de posteo: triggerEvent → (cuenta cargo, cuenta abono). Configurable (T5), sin código.
CREATE TABLE accounting.posting_rules (
    trigger_event  VARCHAR(60) NOT NULL,
    debit_account  VARCHAR(10) NOT NULL,
    credit_account VARCHAR(10) NOT NULL,
    description    VARCHAR(160),
    CONSTRAINT pk_posting_rules PRIMARY KEY (trigger_event),
    CONSTRAINT fk_posting_rules_debit  FOREIGN KEY (debit_account)  REFERENCES accounting.ledger_accounts (code),
    CONSTRAINT fk_posting_rules_credit FOREIGN KEY (credit_account) REFERENCES accounting.ledger_accounts (code)
);
