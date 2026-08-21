--liquibase formatted sql

--changeset payments-service:003-create-account-balance-snapshots
CREATE TABLE payments.account_balance_snapshots (
    credit_account_id           UUID            NOT NULL,
    obligor_party_id            UUID            NOT NULL,
    principal_balance           NUMERIC(19,2)   NOT NULL DEFAULT 0,
    accrued_interest_balance    NUMERIC(19,2)   NOT NULL DEFAULT 0,
    penalty_balance             NUMERIC(19,2)   NOT NULL DEFAULT 0,
    available_credit            NUMERIC(19,2),
    total_debt                  NUMERIC(19,2)   NOT NULL DEFAULT 0,
    credit_limit                NUMERIC(19,2),
    balance_version             BIGINT          NOT NULL DEFAULT 0,
    account_status              VARCHAR(30)     NOT NULL DEFAULT 'PENDING_ACTIVATION',
    snapshot_at                 TIMESTAMPTZ     NOT NULL,
    CONSTRAINT account_balance_snapshots_pk PRIMARY KEY (credit_account_id)
);
