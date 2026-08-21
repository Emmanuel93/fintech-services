--liquibase formatted sql

--changeset audit-service:003-create-document-file-refs
CREATE TABLE audit.document_file_refs (
    doc_id          UUID        NOT NULL,
    doc_type        VARCHAR(60)  NOT NULL,
    party_id        UUID,
    aggregate_id    VARCHAR(120),
    storage_ref     TEXT         NOT NULL,
    retention_years INT          NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    retain_until    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT document_file_refs_pk PRIMARY KEY (doc_id)
);

CREATE INDEX document_file_refs_party_id_idx     ON audit.document_file_refs (party_id) WHERE party_id IS NOT NULL;
CREATE INDEX document_file_refs_aggregate_id_idx ON audit.document_file_refs (aggregate_id) WHERE aggregate_id IS NOT NULL;
CREATE INDEX document_file_refs_doc_type_idx     ON audit.document_file_refs (doc_type);
