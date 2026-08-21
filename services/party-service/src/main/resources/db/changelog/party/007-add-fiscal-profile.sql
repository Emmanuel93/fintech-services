-- CFDI 4.0 fiscal profile — required to invoice (facturar) the party as a receptor.
-- Nullable: captured after onboarding, before the first invoice is issued.
ALTER TABLE party.parties ADD COLUMN tax_name     VARCHAR(300);  -- razón social / nombre fiscal (SAT)
ALTER TABLE party.parties ADD COLUMN tax_regime   VARCHAR(10);   -- régimen fiscal SAT (601, 612, 605, 616, ...)
ALTER TABLE party.parties ADD COLUMN tax_zip_code VARCHAR(5);    -- código postal del domicilio fiscal
ALTER TABLE party.parties ADD COLUMN cfdi_use     VARCHAR(10);   -- uso CFDI por defecto (G03, P01, ...)
ALTER TABLE party.parties ADD COLUMN fiscal_profile_updated_at TIMESTAMPTZ;
