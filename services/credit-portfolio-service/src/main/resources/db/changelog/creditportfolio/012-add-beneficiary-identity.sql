-- Identidad del beneficiario, congelada al originar. STP la exige para firmar la orden de pago
-- (nombre = posición 14, RFC/CURP = posición 16 de la cadena original); disbursement la recibe en
-- credit-account-activated y nunca tiene que preguntarle al dominio de personas.
ALTER TABLE credit_portfolio.credit_accounts
    ADD COLUMN beneficiary_name   VARCHAR(255),
    ADD COLUMN beneficiary_tax_id VARCHAR(18);
