--liquibase formatted sql
--changeset wallet:006-create-wallet-withdrawals author:system

CREATE TABLE wallet.wallet_withdrawals (
    withdrawal_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    credit_account_id UUID         NOT NULL,
    obligor_party_id  UUID         NOT NULL,
    method            VARCHAR(10)  NOT NULL,
    amount            NUMERIC(19,4) NOT NULL,
    payee_account     VARCHAR(30)  NOT NULL,
    status            VARCHAR(15)  NOT NULL DEFAULT 'PENDING',
    external_ref      VARCHAR(100),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_wallet_withdrawals PRIMARY KEY (withdrawal_id),
    CONSTRAINT chk_wallet_withdrawals_method CHECK (method IN ('SPEI', 'CODI')),
    CONSTRAINT chk_wallet_withdrawals_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_wallet_withdrawals_amount CHECK (amount > 0)
);

CREATE INDEX idx_wallet_withdrawals_credit_account ON wallet.wallet_withdrawals (credit_account_id);
