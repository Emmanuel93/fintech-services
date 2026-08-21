--liquibase formatted sql
--changeset invoicing:006-fiscal-profile-prospect-id author:system
--comment El perfil fiscal se busca por el id que traen las facturas, que es el del prospecto.

-- Cartera, contabilidad y facturación llevan el prospectId en `obligorPartyId`: el crédito nace de
-- una solicitud y arrastra el id del prospecto, no el del party creado después. El BFF ya lo sabe y
-- resuelve nombres probando `prospectId` primero; facturación no, así que buscaba el perfil por
-- `partyId`, no encontraba nada y timbraba todo a «público en general» —con folio y sin error—.
--
-- Se indexa porque `resolveReceptor` lo consulta una vez por CFDI en cada corrida de facturación.

ALTER TABLE invoicing.fiscal_profiles ADD COLUMN IF NOT EXISTS prospect_id UUID;

CREATE INDEX IF NOT EXISTS idx_fiscal_profiles_prospect
    ON invoicing.fiscal_profiles (prospect_id)
    WHERE prospect_id IS NOT NULL;
