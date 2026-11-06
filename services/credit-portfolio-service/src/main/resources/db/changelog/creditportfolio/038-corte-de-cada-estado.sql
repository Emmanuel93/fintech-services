--liquibase formatted sql
--changeset credit-portfolio:038-corte-de-cada-estado
--comment The cutoff of the statement each cycle installment stands for.

-- A payment can only cover statements issued before it: one made before a statement's cutoff was already in that
-- statement's balance. To tell them apart the cycle installment keeps the cutoff of its statement. Installments
-- billed before this change have none and keep the cumulative rule.
ALTER TABLE credit_portfolio.installments
    ADD COLUMN IF NOT EXISTS cutoff_date DATE;
