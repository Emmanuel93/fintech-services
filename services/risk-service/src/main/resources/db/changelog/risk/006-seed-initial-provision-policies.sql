--liquibase formatted sql
--changeset risk:006-seed-initial-provision-policies author:system
--comment Placeholder ECL rates — MUST be calibrated with the risk/finance team before production.
--comment What matters day one is the versioned-table mechanism, not the exact numbers.

-- Rate matrix (fraction of EAD) applied per bucket. STAGE_1 low, STAGE_2 SICR, STAGE_3 escalating to full loss.
--   CURRENT 1% | B1_30 3% | B31_60 15% | B61_90 30% | B91_120 60% | B121_180 80% | B181_PLUS 100%

INSERT INTO risk.provision_policies (policy_id, product_type, version, status, created_at) VALUES
    ('11111111-1111-1111-1111-111111111111', 'PERSONAL_LOAN',    1, 'ACTIVE', NOW()),
    ('22222222-2222-2222-2222-222222222222', 'REVOLVING_CREDIT', 1, 'ACTIVE', NOW()),
    ('33333333-3333-3333-3333-333333333333', 'PAYROLL_LOAN',     1, 'ACTIVE', NOW()),
    ('44444444-4444-4444-4444-444444444444', 'SME_LOAN',         1, 'ACTIVE', NOW());

INSERT INTO risk.provision_rate_bands (policy_id, bucket, expected_loss_rate)
SELECT p.policy_id, b.bucket, b.rate
FROM (VALUES
    ('11111111-1111-1111-1111-111111111111'::uuid),
    ('22222222-2222-2222-2222-222222222222'::uuid),
    ('33333333-3333-3333-3333-333333333333'::uuid),
    ('44444444-4444-4444-4444-444444444444'::uuid)
) AS p(policy_id)
CROSS JOIN (VALUES
    ('CURRENT',   0.01000),
    ('B1_30',     0.03000),
    ('B31_60',    0.15000),
    ('B61_90',    0.30000),
    ('B91_120',   0.60000),
    ('B121_180',  0.80000),
    ('B181_PLUS', 1.00000)
) AS b(bucket, rate);
