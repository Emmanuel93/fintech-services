--liquibase formatted sql
--changeset commission:004-create-liquidation-batches author:system

CREATE TABLE commission.liquidation_batches (
    batch_id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    beneficiary_party_id UUID         NOT NULL,
    period               VARCHAR(6)   NOT NULL,
    total_amount          NUMERIC(19,4) NOT NULL,
    status                VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    payment_ref           VARCHAR(100),
    processed_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_liquidation_batches PRIMARY KEY (batch_id),
    CONSTRAINT chk_liquidation_batches_status CHECK (status IN ('PENDING','SENT','CONFIRMED'))
);

CREATE INDEX idx_liquidation_batches_beneficiary ON commission.liquidation_batches (beneficiary_party_id, period);
