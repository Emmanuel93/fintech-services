CREATE TABLE credit_portfolio.installments (
    installment_id      UUID          NOT NULL,
    schedule_id         UUID          NOT NULL,
    installment_number  INTEGER       NOT NULL,
    due_date            DATE          NOT NULL,
    principal_amount    NUMERIC(15,2) NOT NULL,
    interest_amount     NUMERIC(15,2) NOT NULL,
    total_amount        NUMERIC(15,2) NOT NULL,
    status              VARCHAR(10)   NOT NULL DEFAULT 'PENDING',

    CONSTRAINT pk_installments PRIMARY KEY (installment_id),
    CONSTRAINT uq_installment_number UNIQUE (schedule_id, installment_number),
    CONSTRAINT ck_installment_status CHECK (status IN ('PENDING','PAID','OVERDUE','PARTIAL'))
);

CREATE INDEX idx_installments_schedule ON credit_portfolio.installments (schedule_id);
