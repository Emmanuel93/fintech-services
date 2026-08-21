--liquibase formatted sql

--changeset audit-service:004-create-uif-reports
CREATE TABLE audit.uif_reports (
    report_id            UUID        NOT NULL,
    party_id             UUID         NOT NULL,
    report_type          VARCHAR(30)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    trigger_event_type   VARCHAR(120),
    trigger_aggregate_id VARCHAR(120),
    notes                TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    submitted_at         TIMESTAMPTZ,
    CONSTRAINT uif_reports_pk PRIMARY KEY (report_id)
);

CREATE INDEX uif_reports_party_id_idx ON audit.uif_reports (party_id);
CREATE INDEX uif_reports_status_idx   ON audit.uif_reports (status);
