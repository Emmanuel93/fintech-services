--liquibase formatted sql
--changeset origination:013-add-documents-fields author:origination-service
-- PENDING_DOCUMENTS operativo: cuándo vence el plazo para entregar documentos y qué se pidió.
ALTER TABLE origination.credit_applications
    ADD COLUMN IF NOT EXISTS documents_deadline TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS documents_note      VARCHAR(500);

-- Índice parcial para el barrido de TTL: solicitudes en PENDING_DOCUMENTS con plazo vencido.
CREATE INDEX IF NOT EXISTS idx_credit_app_documents_deadline
    ON origination.credit_applications (documents_deadline)
    WHERE status = 'PENDING_DOCUMENTS';
