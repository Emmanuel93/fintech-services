--liquibase formatted sql
--changeset risk:009-ecl-por-etapa
--comment IFRS 9 by stage: 12-month ECL in Stage 1, lifetime in 2 and 3; probation to cure; exposure on undrawn lines.

-- Until now the rate came from the days-past-due band only: a Stage 2 account (forborne, or 30+ DPD) reserved
-- like a Stage 1 one, a cure released the reserve the next day, and four product types (card, revolving line,
-- business line, micro loan) had no policy at all, so 27 % of the exposure carried no reserve (2 Nov E2E).
--
-- The rate applied is max(stage floor, band rate): the stage floor is the 12-month ECL (Stage 1) or the lifetime
-- ECL (Stages 2 and 3); the band keeps the escalation with days past due. Undrawn credit on a revolving line counts
-- in the exposure at its credit conversion factor (IFRS 9 §5.5.20).
--
-- PLACEHOLDER RATES: they must be calibrated with the risk/finance team before production. What this changeset
-- fixes is the mechanism and the coverage of every product.
ALTER TABLE risk.provision_policies
    ADD COLUMN IF NOT EXISTS stage1_rate NUMERIC(6, 5),
    ADD COLUMN IF NOT EXISTS stage2_rate NUMERIC(6, 5),
    ADD COLUMN IF NOT EXISTS stage3_rate NUMERIC(6, 5),
    ADD COLUMN IF NOT EXISTS undrawn_ccf NUMERIC(6, 5) NOT NULL DEFAULT 0;

-- Probation: the day the account first qualified for a better stage; it steps down only once the probation of
-- its current stage has elapsed without falling back. Undrawn: the available credit of a revolving line.
ALTER TABLE risk.risk_profiles
    ADD COLUMN IF NOT EXISTS probation_since TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS undrawn NUMERIC(19, 2) NOT NULL DEFAULT 0;

UPDATE risk.provision_policies SET status = 'DEPRECATED' WHERE status = 'ACTIVE';

CREATE TEMP TABLE ecl_v2 (product_type VARCHAR(40), s1 NUMERIC(6,5), s2 NUMERIC(6,5), s3 NUMERIC(6,5), ccf NUMERIC(6,5),
                          b0 NUMERIC(6,5), b30 NUMERIC(6,5), b60 NUMERIC(6,5), b90 NUMERIC(6,5),
                          b120 NUMERIC(6,5), b180 NUMERIC(6,5), b181 NUMERIC(6,5));
INSERT INTO ecl_v2 VALUES
    ('PERSONAL_LOAN',           0.02000, 0.12000, 0.55000, 0.00000, 0.01000, 0.03000, 0.15000, 0.30000, 0.60000, 0.80000, 1.00000),
    ('PAYROLL_LOAN',            0.01000, 0.08000, 0.45000, 0.00000, 0.00500, 0.02000, 0.10000, 0.25000, 0.50000, 0.75000, 1.00000),
    ('SME_LOAN',                0.01500, 0.10000, 0.50000, 0.00000, 0.01000, 0.03000, 0.15000, 0.30000, 0.60000, 0.80000, 1.00000),
    ('MICRO_LOAN',              0.03000, 0.15000, 0.60000, 0.00000, 0.02000, 0.05000, 0.20000, 0.40000, 0.70000, 0.90000, 1.00000),
    ('GROUP_LOAN',              0.02500, 0.12000, 0.55000, 0.00000, 0.01000, 0.03000, 0.15000, 0.30000, 0.60000, 0.80000, 1.00000),
    ('CREDIT_CARD',             0.03000, 0.15000, 0.60000, 0.20000, 0.02000, 0.05000, 0.20000, 0.40000, 0.70000, 0.90000, 1.00000),
    ('REVOLVING_LINE',          0.03000, 0.15000, 0.60000, 0.20000, 0.02000, 0.05000, 0.20000, 0.40000, 0.70000, 0.90000, 1.00000),
    ('BUSINESS_REVOLVING_LINE', 0.01500, 0.10000, 0.50000, 0.10000, 0.01000, 0.03000, 0.15000, 0.30000, 0.60000, 0.80000, 1.00000),
    ('DISTRIBUTOR_LINE',        0.02000, 0.12000, 0.55000, 0.10000, 0.01500, 0.05000, 0.20000, 0.40000, 0.70000, 0.90000, 1.00000);

INSERT INTO risk.provision_policies (policy_id, product_type, version, status, created_at,
                                     stage1_rate, stage2_rate, stage3_rate, undrawn_ccf)
SELECT gen_random_uuid(), e.product_type,
       coalesce((SELECT max(version) FROM risk.provision_policies p WHERE p.product_type = e.product_type), 0) + 1,
       'ACTIVE', NOW(), e.s1, e.s2, e.s3, e.ccf
  FROM ecl_v2 e;

INSERT INTO risk.provision_rate_bands (policy_id, bucket, expected_loss_rate)
SELECT p.policy_id, b.bucket, b.rate
  FROM risk.provision_policies p
  JOIN ecl_v2 e ON e.product_type = p.product_type
  CROSS JOIN LATERAL (VALUES ('CURRENT', e.b0), ('B1_30', e.b30), ('B31_60', e.b60), ('B61_90', e.b90),
                             ('B91_120', e.b120), ('B121_180', e.b180), ('B181_PLUS', e.b181)) AS b(bucket, rate)
 WHERE p.status = 'ACTIVE';

DROP TABLE ecl_v2;
