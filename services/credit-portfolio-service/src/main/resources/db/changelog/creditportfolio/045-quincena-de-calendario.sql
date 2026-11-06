--liquibase formatted sql
--changeset credit-portfolio:045-quincena-de-calendario
--comment The fortnight (SEMI_MONTHLY, 24 a year) as a payment frequency of the products the portfolio copies.

-- A distributor line's placements pay on the line's cutoffs, the 6th and the 21st: the product publishes
-- SEMI_MONTHLY and its copy here has to accept it, or the product-activated upsert is refused and the catalog never
-- reaches the portfolio.
ALTER TABLE credit_portfolio.product_config_versions DROP CONSTRAINT IF EXISTS ck_pcv_frequency;
ALTER TABLE credit_portfolio.product_config_versions ADD CONSTRAINT ck_pcv_frequency
    CHECK (payment_frequency IS NULL OR payment_frequency IN ('WEEKLY', 'BIWEEKLY', 'SEMI_MONTHLY', 'MONTHLY'));
