--liquibase formatted sql
--changeset origination:019-desembolso-fallido
--comment An application whose credit was cancelled because its disbursement could not happen (D63).

ALTER TABLE origination.credit_applications DROP CONSTRAINT IF EXISTS ck_credit_app_status;
ALTER TABLE origination.credit_applications
    ADD CONSTRAINT ck_credit_app_status CHECK (status IN (
        'DRAFT','PENDING_SCORING','SCORING',
        'UNDER_MANUAL_REVIEW','COMMITTEE_REVIEW',
        'PENDING_DOCUMENTS',
        'APPROVED',
        'OFFER_PRESENTED','OFFER_ACCEPTED','OFFER_EXPIRED','OFFER_REJECTED',
        'PENDING_SIGNATURE','CONTRACT_SIGNED','DISBURSED','DISBURSEMENT_FAILED',
        'REJECTED','FAILED','CANCELLED'));

-- OA-03: one active application per prospect and product. A failed disbursement closes it: the borrower may apply
-- again, so it is not active.
DROP INDEX IF EXISTS origination.uq_credit_app_active_per_product;
CREATE UNIQUE INDEX uq_credit_app_active_per_product
    ON origination.credit_applications (prospect_id, product_type)
    WHERE status NOT IN ('REJECTED','FAILED','CANCELLED','OFFER_REJECTED','OFFER_EXPIRED','DISBURSED',
                         'DISBURSEMENT_FAILED');
