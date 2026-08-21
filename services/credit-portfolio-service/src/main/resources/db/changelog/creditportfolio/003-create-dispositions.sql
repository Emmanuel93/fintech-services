CREATE TABLE credit_portfolio.dispositions (
    disposition_id      UUID         NOT NULL,
    credit_account_id   UUID         NOT NULL,
    disposition_type    VARCHAR(25)  NOT NULL,
    amount              NUMERIC(15,2) NOT NULL,
    beneficiary_party_id UUID,
    status              VARCHAR(15)  NOT NULL DEFAULT 'PENDING',
    external_ref        VARCHAR(100),
    created_at          TIMESTAMPTZ  NOT NULL,
    completed_at        TIMESTAMPTZ,

    CONSTRAINT pk_dispositions PRIMARY KEY (disposition_id),
    CONSTRAINT fk_dispositions_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_portfolio.credit_accounts (credit_account_id),
    CONSTRAINT ck_disposition_status CHECK (status IN (
        'PENDING','PROCESSING','COMPLETED','FAILED','REVERSED')),
    CONSTRAINT ck_disposition_type CHECK (disposition_type IN (
        'SELF_USE','THIRD_PARTY_CREDIT','PAYROLL')),
    CONSTRAINT ck_disposition_amount CHECK (amount > 0)
);

CREATE INDEX idx_dispositions_account ON credit_portfolio.dispositions (credit_account_id);
