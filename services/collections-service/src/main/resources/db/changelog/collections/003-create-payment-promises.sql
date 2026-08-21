--liquibase formatted sql
--changeset collections:003-create-payment-promises author:system

CREATE TABLE collections.payment_promises (
    promise_id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    case_id          UUID         NOT NULL,
    amount           NUMERIC(19,4) NOT NULL,
    promised_date    DATE         NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    recorded_by      VARCHAR(100) NOT NULL,
    linked_payment_id UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_payment_promises PRIMARY KEY (promise_id),
    CONSTRAINT fk_payment_promises_case FOREIGN KEY (case_id)
        REFERENCES collections.collection_cases (case_id),
    CONSTRAINT chk_payment_promises_status CHECK (status IN ('ACTIVE','KEPT','BROKEN','EXPIRED')),
    CONSTRAINT chk_payment_promises_amount CHECK (amount > 0)
);

CREATE INDEX idx_payment_promises_case ON collections.payment_promises (case_id);
-- PP-01: only one ACTIVE promise per case at a time
CREATE UNIQUE INDEX idx_payment_promises_active_per_case
    ON collections.payment_promises (case_id)
    WHERE status = 'ACTIVE';
