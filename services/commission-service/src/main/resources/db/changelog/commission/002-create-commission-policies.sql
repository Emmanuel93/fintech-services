--liquibase formatted sql
--changeset commission:002-create-commission-policies author:system

CREATE TABLE commission.commission_policies (
    policy_id            UUID          NOT NULL DEFAULT gen_random_uuid(),
    product_type         VARCHAR(40)   NOT NULL,
    distributor_party_id UUID,                        -- NULL = default rate for the product
    commission_type      VARCHAR(30)   NOT NULL,
    rate                 NUMERIC(6,5)  NOT NULL,       -- % of interest collected
    version              INT           NOT NULL,
    status               VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_commission_policies PRIMARY KEY (policy_id),
    CONSTRAINT chk_commission_policies_status CHECK (status IN ('DRAFT','ACTIVE','DEPRECATED')),
    CONSTRAINT chk_commission_policies_rate CHECK (rate >= 0 AND rate <= 1)
);

-- CP-01: one ACTIVE policy per (productType, commissionType, distributorPartyId) — NULLS are distinct
-- in Postgres unique indexes, so the "default" row (distributor_party_id IS NULL) coexists safely
-- with per-distributor overrides.
CREATE UNIQUE INDEX idx_commission_policies_active
    ON commission.commission_policies (product_type, commission_type, distributor_party_id)
    WHERE status = 'ACTIVE';
