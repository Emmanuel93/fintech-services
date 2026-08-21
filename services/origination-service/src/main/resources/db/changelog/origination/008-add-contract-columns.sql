-- Phase F: contract terms embedded in credit_applications
ALTER TABLE origination.credit_applications
    ADD COLUMN contract_number     VARCHAR(40),
    ADD COLUMN signature_method    VARCHAR(30),
    ADD COLUMN clabe_account       VARCHAR(18),
    ADD COLUMN document_ref        VARCHAR(200),
    ADD COLUMN contract_signed_at  TIMESTAMPTZ;
