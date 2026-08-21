--liquibase formatted sql
--changeset collections:007-create-bureau-reports author:system

CREATE TABLE collections.bureau_reports (
    report_id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    credit_account_id UUID         NOT NULL,
    obligor_party_id  UUID         NOT NULL,
    event_type        VARCHAR(15)  NOT NULL,
    source_record_id  UUID         NOT NULL,
    amount_reported   NUMERIC(19,4) NOT NULL,
    status            VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    bureau_reference  VARCHAR(100),
    submitted_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_bureau_reports PRIMARY KEY (report_id),
    CONSTRAINT chk_bureau_reports_event_type CHECK (event_type IN ('WRITE_OFF','QUITA_PARCIAL')),
    CONSTRAINT chk_bureau_reports_status CHECK (status IN ('PENDING','SUBMITTED','FAILED'))
);

CREATE INDEX idx_bureau_reports_account ON collections.bureau_reports (credit_account_id);
CREATE INDEX idx_bureau_reports_status ON collections.bureau_reports (status);
-- BR-01: one report per source record — never report the same write-off/quita twice
CREATE UNIQUE INDEX idx_bureau_reports_source ON collections.bureau_reports (source_record_id);
