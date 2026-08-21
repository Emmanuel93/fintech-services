--liquibase formatted sql
--changeset wallet:002-create-wallet-views author:system

CREATE TABLE wallet.wallet_views (
    wallet_id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    credit_account_id        UUID         NOT NULL,
    obligor_party_id         UUID         NOT NULL,
    product_type             VARCHAR(30)  NOT NULL,
    principal_balance        NUMERIC(19,4) NOT NULL DEFAULT 0,
    accrued_interest_balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    penalty_balance          NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_debt               NUMERIC(19,4) NOT NULL DEFAULT 0,
    available_credit         NUMERIC(19,4),
    minimum_payment          NUMERIC(19,4),
    payment_due_date         DATE,
    next_installment_amount  NUMERIC(19,4),
    status                   VARCHAR(30)  NOT NULL,
    last_updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    registered_clabe         VARCHAR(18),
    balance_version          BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_wallet_views PRIMARY KEY (wallet_id),
    CONSTRAINT uq_wallet_views_credit_account UNIQUE (credit_account_id),
    CONSTRAINT chk_wallet_views_status CHECK (status IN (
        'ACTIVE','SUSPENDED','DELINQUENT','SETTLED','WRITTEN_OFF','UNKNOWN'
    ))
);

CREATE INDEX idx_wallet_views_credit_account ON wallet.wallet_views (credit_account_id);
CREATE INDEX idx_wallet_views_obligor ON wallet.wallet_views (obligor_party_id);
CREATE INDEX idx_wallet_views_status ON wallet.wallet_views (status);
