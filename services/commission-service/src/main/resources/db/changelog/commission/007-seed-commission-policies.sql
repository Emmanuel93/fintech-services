--liquibase formatted sql
--changeset commission:007-seed-commission-policies author:system
--comment Placeholder rates — MUST be calibrated with the commercial team before production.
--comment DISTRIBUTOR_INTEREST_SHARE: % of interest collected paid to the B2B2C distributor, per product.

INSERT INTO commission.commission_policies (policy_id, product_type, distributor_party_id, commission_type, rate, version, status, created_at) VALUES
    ('a1111111-1111-1111-1111-111111111111', 'DISTRIBUTOR_LINE', NULL, 'DISTRIBUTOR_INTEREST_SHARE', 0.30000, 1, 'ACTIVE', NOW()),
    ('a2222222-2222-2222-2222-222222222222', 'SME_LOAN',          NULL, 'DISTRIBUTOR_INTEREST_SHARE', 0.20000, 1, 'ACTIVE', NOW());
