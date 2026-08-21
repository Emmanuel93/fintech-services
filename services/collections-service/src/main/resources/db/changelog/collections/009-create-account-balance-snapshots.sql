--liquibase formatted sql
--changeset collections:009-create-account-balance-snapshots author:system

CREATE TABLE collections.account_balance_snapshots (
    credit_account_id        UUID         NOT NULL,
    obligor_party_id         UUID         NOT NULL,
    product_type              VARCHAR(40)  NOT NULL DEFAULT 'UNKNOWN',
    principal_balance        NUMERIC(19,4) NOT NULL DEFAULT 0,
    accrued_interest_balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    penalty_balance          NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_debt               NUMERIC(19,4) NOT NULL DEFAULT 0,
    balance_version          BIGINT       NOT NULL DEFAULT -1,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_account_balance_snapshots PRIMARY KEY (credit_account_id)
);
