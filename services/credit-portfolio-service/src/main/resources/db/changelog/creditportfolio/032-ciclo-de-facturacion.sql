--liquibase formatted sql
--changeset credit-portfolio:032-ciclo-de-facturacion
--comment The product's billing cycle travels with its configuration and is pinned to each account on activation.

ALTER TABLE credit_portfolio.product_config_versions
    ADD COLUMN IF NOT EXISTS billing_cycle JSONB;
