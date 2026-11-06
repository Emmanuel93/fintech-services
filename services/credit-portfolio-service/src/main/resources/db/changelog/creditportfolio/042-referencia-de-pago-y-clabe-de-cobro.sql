--liquibase formatted sql
--changeset credit-portfolio:042-referencia-de-pago-y-clabe-de-cobro
--comment Each loan's payment reference (the SPEI numeric reference that names it) and each person's collection CLABE.

-- A person pays all their loans to one collection CLABE; the numeric reference of the transfer says which loan. It is
-- 7 digits (the SPEI field's size): a 6-digit consecutive plus a Luhn check digit, so a mistyped digit names no loan
-- instead of someone else's. Assigned at activation; loans opened before are given one at startup.
ALTER TABLE credit_portfolio.credit_accounts ADD COLUMN payment_reference BIGINT;
CREATE UNIQUE INDEX uq_credit_accounts_payment_reference
    ON credit_portfolio.credit_accounts (payment_reference) WHERE payment_reference IS NOT NULL;
CREATE SEQUENCE IF NOT EXISTS credit_portfolio.payment_reference_seq START WITH 100000 MAXVALUE 999999;

-- The person's collection CLABE as the SPEI connector assigned it: printed on every statement with the reference.
CREATE TABLE IF NOT EXISTS credit_portfolio.collection_accounts (
    party_id              UUID        NOT NULL,
    collection_account_id UUID        NOT NULL,
    clabe                 VARCHAR(18) NOT NULL,
    status                VARCHAR(12) NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_collection_accounts PRIMARY KEY (party_id)
);
