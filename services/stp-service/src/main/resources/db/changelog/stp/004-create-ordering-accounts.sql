--liquibase formatted sql

--changeset stp-service:004-create-ordering-accounts
CREATE TABLE stp.ordering_accounts (
    ordering_account_id UUID         NOT NULL,
    company_id          UUID         NOT NULL,
    clabe               VARCHAR(18)  NOT NULL,
    holder_name         VARCHAR(150) NOT NULL,
    tax_id              VARCHAR(18),
    account_type        VARCHAR(4)   NOT NULL DEFAULT '40',
    -- VARCHAR y no CHAR: ver 002-create-companies.sql.
    currency            VARCHAR(3)   NOT NULL DEFAULT 'MXN',
    stp_client_number   VARCHAR(20),
    is_default          BOOLEAN      NOT NULL DEFAULT FALSE,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ordering_accounts_pk PRIMARY KEY (ordering_account_id),
    CONSTRAINT ordering_accounts_clabe_uq UNIQUE (clabe)
);

-- Una sola cuenta por default y activa por empresa.
CREATE UNIQUE INDEX ordering_accounts_default_uq
    ON stp.ordering_accounts (company_id) WHERE is_default AND active;
