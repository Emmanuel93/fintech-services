--liquibase formatted sql
--changeset accounting:017-tasa-efectiva
--comment Effective interest (IFRS 9 B5.4.1-B5.4.8): fees and transaction costs of origination amortize into interest revenue.

-- The opening fee is part of the effective interest rate: its monthly share is interest revenue, not fee income.
-- It went to 4103 (fees); it goes to 4106, inside interest revenue, apart from the contractual interest (4101).
-- A commission paid to the promoter for originating a loan is a transaction cost (B5.4.8): it is deferred like the
-- fee, with the opposite sign — an asset (1205) amortized against the same interest revenue.
INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('1205', 'Costos de originación por amortizar', 'ASSET'),
    ('4106', 'Ingresos por intereses: comisiones y costos de originación (tasa efectiva)', 'INCOME')
ON CONFLICT (code) DO NOTHING;

ALTER TABLE accounting.deferred_fees ADD COLUMN IF NOT EXISTS kind VARCHAR(4) NOT NULL DEFAULT 'FEE';
ALTER TABLE accounting.deferred_fees ADD CONSTRAINT chk_deferred_fees_kind CHECK (kind IN ('FEE', 'COST'));
