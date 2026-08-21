--liquibase formatted sql
--changeset wallet:003-create-payment-instructions author:system

CREATE TABLE wallet.payment_instructions (
    instruction_id   UUID         NOT NULL DEFAULT gen_random_uuid(),
    credit_account_id UUID        NOT NULL,
    obligor_party_id UUID         NOT NULL,
    payment_method   VARCHAR(20)  NOT NULL,
    amount           NUMERIC(19,4) NOT NULL,
    payment_type     VARCHAR(15)  NOT NULL,
    scheduled_at     TIMESTAMPTZ,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    expires_at       TIMESTAMPTZ  NOT NULL,
    payment_ref      VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_payment_instructions PRIMARY KEY (instruction_id),
    CONSTRAINT chk_payment_instructions_method CHECK (payment_method IN (
        'SPEI','CODI','DOMICILIACION','VENTANILLA','TARJETA'
    )),
    CONSTRAINT chk_payment_instructions_type CHECK (payment_type IN (
        'MINIMUM','TOTAL','PARTIAL','SETTLEMENT'
    )),
    CONSTRAINT chk_payment_instructions_status CHECK (status IN (
        'PENDING','SENT','CANCELLED','EXPIRED'
    ))
);

CREATE INDEX idx_payment_instructions_credit_account ON wallet.payment_instructions (credit_account_id);
CREATE INDEX idx_payment_instructions_status ON wallet.payment_instructions (credit_account_id, status);
-- Supports PI-06: one PENDING per (creditAccountId, paymentMethod)
CREATE UNIQUE INDEX idx_payment_instructions_pending_method
    ON wallet.payment_instructions (credit_account_id, payment_method)
    WHERE status = 'PENDING';
