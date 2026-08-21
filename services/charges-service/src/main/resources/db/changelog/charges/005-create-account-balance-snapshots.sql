-- liquibase formatted sql

-- changeset charges:005-create-account-balance-snapshots
CREATE TABLE IF NOT EXISTS charges.account_balance_snapshots (
    credit_account_id         UUID          NOT NULL,
    obligor_party_id          UUID          NOT NULL,
    principal_balance         NUMERIC(19,4) NOT NULL DEFAULT 0,
    accrued_interest_balance  NUMERIC(19,4) NOT NULL DEFAULT 0,
    penalty_balance           NUMERIC(19,4) NOT NULL DEFAULT 0,
    available_credit          NUMERIC(19,4),
    total_debt                NUMERIC(19,4) NOT NULL DEFAULT 0,
    credit_limit              NUMERIC(19,4),
    balance_version           BIGINT        NOT NULL DEFAULT 0,
    account_status            VARCHAR(30)   NOT NULL,
    snapshot_at               TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_charges_account_balance_snapshots PRIMARY KEY (credit_account_id)
);

CREATE INDEX IF NOT EXISTS idx_charges_balance_snapshots_status
    ON charges.account_balance_snapshots (account_status);
