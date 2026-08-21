--liquibase formatted sql
--changeset collections:002-create-collection-cases author:system

CREATE TABLE collections.collection_cases (
    case_id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    credit_account_id UUID         NOT NULL,
    obligor_party_id  UUID         NOT NULL,
    product_type      VARCHAR(40)  NOT NULL,
    status            VARCHAR(15)  NOT NULL DEFAULT 'OPEN',
    current_bucket    VARCHAR(15)  NOT NULL,
    days_delinquent   INT          NOT NULL,
    total_debt        NUMERIC(19,4) NOT NULL,
    assigned_agent_id VARCHAR(100),
    external_agency_id VARCHAR(100),
    strategy          VARCHAR(50),
    opened_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at         TIMESTAMPTZ,

    CONSTRAINT pk_collection_cases PRIMARY KEY (case_id),
    CONSTRAINT chk_collection_cases_status CHECK (status IN ('OPEN','MANAGED','LEGAL','WRITTEN_OFF','CLOSED')),
    CONSTRAINT chk_collection_cases_bucket CHECK (current_bucket IN
        ('CURRENT','B1_30','B31_60','B61_90','B91_120','B121_180','B181_PLUS'))
);

CREATE INDEX idx_collection_cases_account ON collections.collection_cases (credit_account_id);
CREATE INDEX idx_collection_cases_party ON collections.collection_cases (obligor_party_id);
-- CC-01: only one OPEN/MANAGED/LEGAL case per creditAccountId at a time
CREATE UNIQUE INDEX idx_collection_cases_active_per_account
    ON collections.collection_cases (credit_account_id)
    WHERE status IN ('OPEN', 'MANAGED', 'LEGAL');
