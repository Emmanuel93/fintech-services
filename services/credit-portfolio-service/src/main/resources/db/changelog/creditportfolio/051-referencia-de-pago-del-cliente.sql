--liquibase formatted sql
--changeset credit-portfolio:051-referencia-de-pago-del-cliente
--comment A client's payment reference on a distributor's line: printed on his receipt, it lands his deposit on his installments.

-- One per (line, client), for all his placements with her. Seven digits with a Luhn check from the same sequence as
-- the loans' references (payment_reference_seq): a reference names a loan or a client, never both, so a deposit's
-- reference is never ambiguous.
CREATE TABLE credit_portfolio.client_payment_references (
    payment_reference    BIGINT       NOT NULL,
    credit_account_id    UUID         NOT NULL,
    obligor_party_id     UUID         NOT NULL,
    beneficiary_party_id UUID         NOT NULL,
    assigned_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_client_payment_references PRIMARY KEY (payment_reference),
    CONSTRAINT uq_client_payment_references_line_client UNIQUE (credit_account_id, beneficiary_party_id)
);
CREATE INDEX idx_client_payment_references_client ON credit_portfolio.client_payment_references (beneficiary_party_id);

COMMENT ON TABLE credit_portfolio.client_payment_references IS
    'Referencia de pago del cliente en la línea de su distribuidora: un depósito a la CLABE de ella con esta referencia se aplica a las cuotas de él';
