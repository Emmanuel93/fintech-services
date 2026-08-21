--liquibase formatted sql
--changeset invoicing:002-create-fiscal-profiles author:system
-- Proyección local del perfil fiscal del party (receptor del CFDI), desde party.fiscal-profile-updated.
CREATE TABLE invoicing.fiscal_profiles (
    party_id     UUID         NOT NULL,
    party_type   VARCHAR(20)  NOT NULL,
    rfc          VARCHAR(13),
    tax_name     VARCHAR(300),
    tax_regime   VARCHAR(10),
    tax_zip_code VARCHAR(5),
    cfdi_use     VARCHAR(10),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_fiscal_profiles PRIMARY KEY (party_id)
);
