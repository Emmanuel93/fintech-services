--liquibase formatted sql
--changeset accounting:025-credito-cancelado
--comment A credit cancelled because its disbursement failed (D63): its pending fee and IVA never reach a CFDI.

ALTER TABLE accounting.invoiceable_items DROP CONSTRAINT IF EXISTS chk_invoiceable_items_status;
ALTER TABLE accounting.invoiceable_items
    ADD CONSTRAINT chk_invoiceable_items_status CHECK (status IN ('PENDING', 'BILLED', 'CANCELLED'));

CREATE INDEX IF NOT EXISTS idx_invoiceable_items_account_status
    ON accounting.invoiceable_items (credit_account_id, status);
