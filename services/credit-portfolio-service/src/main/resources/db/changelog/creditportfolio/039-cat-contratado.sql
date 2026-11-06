--liquibase formatted sql
--changeset credit-portfolio:039-cat-contratado
--comment The CAT of the offer the client signed (annual, %, without IVA).
-- The statement prints it (DT-ENR art. 36-IX and 37): it is a term of the contract, sealed at signature and never
-- recomputed. Null for accounts opened before.
ALTER TABLE credit_portfolio.credit_accounts ADD COLUMN IF NOT EXISTS cat NUMERIC(8,2);
