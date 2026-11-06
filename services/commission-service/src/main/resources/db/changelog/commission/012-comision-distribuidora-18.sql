--liquibase formatted sql
--changeset commission:012-comision-distribuidora-18
--comment The distributor keeps 18 % of the interest her clients' payments collect: the rate the business set for the line.

-- 007 seeded 30 % as a placeholder to be calibrated. The business set it: the distributor («la experta») takes 18 % of
-- the interest each payment collects, on a line that places micro-loans at 95 % a year. Version 1 is kept, deprecated:
-- every commission already accrued carries the rate it was accrued with.
UPDATE commission.commission_policies
   SET status = 'DEPRECATED'
 WHERE product_type = 'DISTRIBUTOR_LINE' AND commission_type = 'DISTRIBUTOR_INTEREST_SHARE'
   AND distributor_party_id IS NULL AND status = 'ACTIVE';

INSERT INTO commission.commission_policies (policy_id, product_type, distributor_party_id, commission_type, rate, version, status, created_at)
VALUES ('a1111111-1111-1111-1111-111111111112', 'DISTRIBUTOR_LINE', NULL, 'DISTRIBUTOR_INTEREST_SHARE', 0.18000, 2, 'ACTIVE', NOW())
ON CONFLICT DO NOTHING;
