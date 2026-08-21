--liquibase formatted sql

--changeset charges-service:002-create-accrual-schedules
CREATE TABLE charges.accrual_schedules (
    schedule_id             UUID        NOT NULL,
    credit_account_id       UUID        NOT NULL,
    obligor_party_id        UUID        NOT NULL,
    product_type            VARCHAR(50) NOT NULL,
    product_behavior        VARCHAR(50) NOT NULL,
    status                  VARCHAR(20) NOT NULL
        CONSTRAINT accrual_schedules_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    nominal_rate            NUMERIC(12,8) NOT NULL,
    moratorium_rate         NUMERIC(12,8) NOT NULL,
    moratorium_active       BOOLEAN     NOT NULL DEFAULT FALSE,
    moratorium_start_date   DATE,
    grace_period_days       INTEGER     NOT NULL DEFAULT 3,
    last_accrual_date       DATE,
    principal_balance       NUMERIC(19,2) NOT NULL,
    approved_amount         NUMERIC(19,2) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT accrual_schedules_pk  PRIMARY KEY (schedule_id),
    CONSTRAINT accrual_schedules_account_uq UNIQUE (credit_account_id)
);

CREATE INDEX accrual_schedules_status_idx
    ON charges.accrual_schedules (status);

CREATE INDEX accrual_schedules_status_mora_idx
    ON charges.accrual_schedules (status, moratorium_active)
    WHERE moratorium_active = TRUE;
