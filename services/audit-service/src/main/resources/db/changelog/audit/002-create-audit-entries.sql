--liquibase formatted sql

--changeset audit-service:002-create-audit-entries
CREATE TABLE audit.audit_entries (
    entry_id        UUID        NOT NULL,
    event_type      VARCHAR(120) NOT NULL,
    domain_source   VARCHAR(80)  NOT NULL,
    aggregate_id    VARCHAR(120),
    party_id        VARCHAR(120),
    correlation_id  VARCHAR(120),
    payload         TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT audit_entries_pk PRIMARY KEY (entry_id)
);

CREATE INDEX audit_entries_party_id_idx      ON audit.audit_entries (party_id) WHERE party_id IS NOT NULL;
CREATE INDEX audit_entries_aggregate_id_idx  ON audit.audit_entries (aggregate_id) WHERE aggregate_id IS NOT NULL;
CREATE INDEX audit_entries_event_type_idx    ON audit.audit_entries (event_type);
CREATE INDEX audit_entries_created_at_idx    ON audit.audit_entries (created_at DESC);
