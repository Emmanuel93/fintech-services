CREATE TABLE credit_portfolio.credit_accounts (
    credit_account_id        UUID         NOT NULL,
    contract_id              UUID         NOT NULL,
    contract_number          VARCHAR(40)  NOT NULL,
    product_code             VARCHAR(50)  NOT NULL,
    product_version          INTEGER,
    product_type             VARCHAR(30)  NOT NULL,
    product_behavior         VARCHAR(20)  NOT NULL,
    obligor_party_id         UUID         NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'PENDING_ACTIVATION',
    nominal_rate             NUMERIC(8,4) NOT NULL,
    moratorium_rate          NUMERIC(8,4) NOT NULL,
    opening_fee_rate         NUMERIC(8,4) NOT NULL DEFAULT 0,
    assigned_term            INTEGER,
    credit_limit             NUMERIC(15,2),
    principal_balance        NUMERIC(15,2) NOT NULL DEFAULT 0,
    accrued_interest_balance NUMERIC(15,2) NOT NULL DEFAULT 0,
    penalty_balance          NUMERIC(15,2) NOT NULL DEFAULT 0,
    available_credit         NUMERIC(15,2),
    amortization_type        VARCHAR(20),
    risk_tier                VARCHAR(10),
    clabe_account            VARCHAR(18),
    days_delinquent          INTEGER       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    activated_at             TIMESTAMPTZ,

    CONSTRAINT pk_credit_accounts PRIMARY KEY (credit_account_id),
    CONSTRAINT uq_credit_accounts_contract UNIQUE (contract_id),
    CONSTRAINT ck_credit_account_status CHECK (status IN (
        'PENDING_ACTIVATION','ACTIVE','SUSPENDED','RESTRUCTURED','SETTLED','WRITTEN_OFF','CLOSED')),
    CONSTRAINT ck_credit_account_behavior CHECK (product_behavior IN ('INSTALLMENT','REVOLVING')),
    CONSTRAINT ck_credit_account_principal CHECK (principal_balance >= 0),
    CONSTRAINT ck_credit_account_interest  CHECK (accrued_interest_balance >= 0),
    CONSTRAINT ck_credit_account_penalty   CHECK (penalty_balance >= 0)
);

CREATE INDEX idx_credit_accounts_obligor ON credit_portfolio.credit_accounts (obligor_party_id);
CREATE INDEX idx_credit_accounts_status  ON credit_portfolio.credit_accounts (status);
