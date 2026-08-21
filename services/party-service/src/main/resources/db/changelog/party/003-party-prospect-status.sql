-- Party se crea ahora como PROSPECT (desde origination.prospect-created).
-- Los campos de scoring son opcionales y se poblarán cuando scoring complete.

-- Agregar PROSPECT al status check constraint
ALTER TABLE party.parties DROP CONSTRAINT ck_party_status;
ALTER TABLE party.parties ADD CONSTRAINT ck_party_status
    CHECK (status IN ('PROSPECT', 'ACTIVE', 'SUSPENDED', 'BLACKLISTED', 'CLOSED'));

ALTER TABLE party.parties ALTER COLUMN status SET DEFAULT 'PROSPECT';

-- evaluation_id, risk_level, total_score son nulos hasta que scoring complete
ALTER TABLE party.parties ALTER COLUMN evaluation_id DROP NOT NULL;
ALTER TABLE party.parties ALTER COLUMN risk_level    DROP NOT NULL;
ALTER TABLE party.parties ALTER COLUMN total_score   DROP NOT NULL;
ALTER TABLE party.parties ALTER COLUMN total_score   DROP DEFAULT;

-- risk_level puede ser null, pero cuando existe debe ser válido
ALTER TABLE party.parties DROP CONSTRAINT IF EXISTS ck_risk_level;
ALTER TABLE party.parties ADD CONSTRAINT ck_risk_level
    CHECK (risk_level IS NULL OR risk_level IN ('BAJO', 'MEDIO', 'ALTO'));
