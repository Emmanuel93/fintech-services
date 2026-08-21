--liquibase formatted sql
--changeset wallet:005-add-wallet-balance author:system

ALTER TABLE wallet.wallet_views
    ADD COLUMN wallet_balance NUMERIC(19,4) NOT NULL DEFAULT 0;
