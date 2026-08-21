--liquibase formatted sql
--changeset risk:003-create-provision-policies author:system

CREATE TABLE risk.provision_policies (
    policy_id    UUID        NOT NULL DEFAULT gen_random_uuid(),
    product_type VARCHAR(40) NOT NULL,
    version      INT         NOT NULL,
    status       VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_provision_policies PRIMARY KEY (policy_id),
    CONSTRAINT chk_provision_policies_status CHECK (status IN ('DRAFT','ACTIVE','DEPRECATED'))
);

-- PP-01: only one ACTIVE ProvisionPolicy per productType (same pattern as credit_product_definitions)
CREATE UNIQUE INDEX idx_provision_policies_active_per_product
    ON risk.provision_policies (product_type)
    WHERE status = 'ACTIVE';
