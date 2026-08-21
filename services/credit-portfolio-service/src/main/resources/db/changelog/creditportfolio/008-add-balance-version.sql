--liquibase formatted sql

--changeset credit-portfolio-service:008-add-balance-version
ALTER TABLE credit_portfolio.credit_accounts
    ADD COLUMN IF NOT EXISTS balance_version BIGINT NOT NULL DEFAULT 0;
