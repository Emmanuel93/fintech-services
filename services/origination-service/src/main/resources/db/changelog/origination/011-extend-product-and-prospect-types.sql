-- Amplía los tipos permitidos para habilitar los tres segmentos del negocio:
--   B2B  (BUSINESS): productos SME_LOAN y BUSINESS_REVOLVING_LINE.
--   B2B2C (DISTRIBUTOR): prospecto tipo DISTRIBUTOR para la línea de distribuidor.
-- El comportamiento (INSTALLMENT/REVOLVING) y el pricing los define el catálogo
-- (credit-product-service), no el nombre del enum — por eso ampliar es aditivo y seguro.

ALTER TABLE origination.credit_applications DROP CONSTRAINT ck_credit_app_product;
ALTER TABLE origination.credit_applications
    ADD CONSTRAINT ck_credit_app_product CHECK (product_type IN (
        'PERSONAL_LOAN', 'REVOLVING_LINE', 'PAYROLL_LOAN', 'GROUP_LOAN', 'DISTRIBUTOR_LINE',
        'SME_LOAN', 'BUSINESS_REVOLVING_LINE'));

ALTER TABLE origination.credit_applications DROP CONSTRAINT ck_credit_app_prospect_type;
ALTER TABLE origination.credit_applications
    ADD CONSTRAINT ck_credit_app_prospect_type CHECK (prospect_type IN (
        'INDIVIDUAL', 'BUSINESS', 'DISTRIBUTOR'));

ALTER TABLE origination.prospects DROP CONSTRAINT ck_prospect_type;
ALTER TABLE origination.prospects
    ADD CONSTRAINT ck_prospect_type CHECK (prospect_type IN (
        'INDIVIDUAL', 'BUSINESS', 'DISTRIBUTOR'));
