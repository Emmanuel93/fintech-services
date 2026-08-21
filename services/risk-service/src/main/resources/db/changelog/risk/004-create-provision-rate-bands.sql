--liquibase formatted sql
--changeset risk:004-create-provision-rate-bands author:system

CREATE TABLE risk.provision_rate_bands (
    id                 UUID         NOT NULL DEFAULT gen_random_uuid(),
    policy_id          UUID         NOT NULL,
    bucket             VARCHAR(15)  NOT NULL,
    expected_loss_rate NUMERIC(6,5) NOT NULL,

    CONSTRAINT pk_provision_rate_bands PRIMARY KEY (id),
    CONSTRAINT fk_provision_rate_bands_policy FOREIGN KEY (policy_id)
        REFERENCES risk.provision_policies (policy_id) ON DELETE CASCADE,
    -- PP-02: one band per bucket within a policy
    CONSTRAINT uq_provision_rate_bands_policy_bucket UNIQUE (policy_id, bucket),
    CONSTRAINT chk_provision_rate_bands_bucket CHECK (bucket IN
        ('CURRENT','B1_30','B31_60','B61_90','B91_120','B121_180','B181_PLUS')),
    CONSTRAINT chk_provision_rate_bands_rate CHECK (expected_loss_rate >= 0 AND expected_loss_rate <= 1)
);

CREATE INDEX idx_provision_rate_bands_policy ON risk.provision_rate_bands (policy_id);
