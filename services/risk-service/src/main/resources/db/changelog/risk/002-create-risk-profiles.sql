--liquibase formatted sql
--changeset risk:002-create-risk-profiles author:system

CREATE TABLE risk.risk_profiles (
    risk_profile_id    UUID          NOT NULL DEFAULT gen_random_uuid(),
    credit_account_id  UUID          NOT NULL,
    obligor_party_id   UUID          NOT NULL,
    product_type       VARCHAR(40)   NOT NULL,
    days_delinquent    INT           NOT NULL DEFAULT 0,
    bucket             VARCHAR(15)   NOT NULL DEFAULT 'CURRENT',
    ifrs9_stage        VARCHAR(10)   NOT NULL DEFAULT 'STAGE_1',
    stage_entered_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    is_forborne        BOOLEAN       NOT NULL DEFAULT FALSE,
    ead                NUMERIC(19,4) NOT NULL DEFAULT 0,
    expected_loss_rate NUMERIC(6,5)  NOT NULL DEFAULT 0,
    provision_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
    status             VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    last_calculated_at TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_risk_profiles PRIMARY KEY (risk_profile_id),
    -- one RiskProfile per credit account (1:1 with CreditAccount)
    CONSTRAINT uq_risk_profiles_account UNIQUE (credit_account_id),
    CONSTRAINT chk_risk_profiles_bucket CHECK (bucket IN
        ('CURRENT','B1_30','B31_60','B61_90','B91_120','B121_180','B181_PLUS')),
    CONSTRAINT chk_risk_profiles_stage CHECK (ifrs9_stage IN ('STAGE_1','STAGE_2','STAGE_3')),
    CONSTRAINT chk_risk_profiles_status CHECK (status IN ('ACTIVE','CLOSED'))
);

CREATE INDEX idx_risk_profiles_party ON risk.risk_profiles (obligor_party_id);
-- PR-01: the nightly job scans all ACTIVE profiles
CREATE INDEX idx_risk_profiles_status ON risk.risk_profiles (status);
CREATE INDEX idx_risk_profiles_product_stage ON risk.risk_profiles (product_type, ifrs9_stage);
