--liquibase formatted sql
--changeset commission:003-create-commission-records author:system

CREATE TABLE commission.commission_records (
    commission_id      UUID          NOT NULL DEFAULT gen_random_uuid(),
    commission_type    VARCHAR(30)   NOT NULL,
    credit_account_id  UUID          NOT NULL,
    beneficiary_party_id UUID        NOT NULL,
    source_event_id    VARCHAR(120)  NOT NULL,
    basis              NUMERIC(19,4) NOT NULL,   -- interest collected in that payment
    rate               NUMERIC(6,5)  NOT NULL,   -- snapshot of the policy rate at accrual time
    amount             NUMERIC(19,4) NOT NULL,   -- basis * rate
    status              VARCHAR(12)  NOT NULL DEFAULT 'ACCRUED',
    period              VARCHAR(6)   NOT NULL,   -- YYYYMM
    accrual_date        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    liquidation_batch_id UUID,

    CONSTRAINT pk_commission_records PRIMARY KEY (commission_id),
    -- CR-04: idempotent — one accrual per source payment event
    CONSTRAINT uq_commission_records_source UNIQUE (source_event_id),
    CONSTRAINT chk_commission_records_status CHECK (status IN ('ACCRUED','LIQUIDATED','REVERSED'))
);

CREATE INDEX idx_commission_records_account ON commission.commission_records (credit_account_id);
CREATE INDEX idx_commission_records_beneficiary_period ON commission.commission_records (beneficiary_party_id, period, status);
CREATE INDEX idx_commission_records_batch ON commission.commission_records (liquidation_batch_id);
