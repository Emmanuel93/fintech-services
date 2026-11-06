--liquibase formatted sql
--changeset payments:007-referencia-del-cliente author:system
--comment A distributor's client pays his own loans: his reference on her CLABE names him, and the payment says it was his.

-- The directory of the clients' references, as the portfolio assigns them (credit-portfolio.client-reference-assigned):
-- a deposit to a distributor's CLABE with one of them is her client paying his loans with her.
CREATE TABLE payments.client_references (
    payment_reference     BIGINT       NOT NULL,
    credit_account_id     UUID         NOT NULL,
    obligor_party_id      UUID,
    beneficiary_party_id  UUID         NOT NULL,
    recorded_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT client_references_pk PRIMARY KEY (payment_reference)
);

-- A client's deposit is allocated to her line as BENEFICIARY, and the order and the allocation keep whose it was.
ALTER TABLE payments.credit_allocations DROP CONSTRAINT credit_allocations_mode_chk;
ALTER TABLE payments.credit_allocations
    ADD CONSTRAINT credit_allocations_mode_chk CHECK (mode IN ('REFERENCED', 'BY_RULE', 'CREDIT_BALANCE', 'BENEFICIARY')),
    ADD COLUMN beneficiary_party_id UUID;
ALTER TABLE payments.payment_orders ADD COLUMN beneficiary_party_id UUID;

COMMENT ON TABLE payments.client_references IS
    'Referencias de pago de los clientes de las distribuidoras: un abono a la CLABE de ella con una de ellas es el cliente pagando sus préstamos';
