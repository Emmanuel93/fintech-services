--liquibase formatted sql
--changeset collections:005-create-write-off-records author:system

CREATE TABLE collections.write_off_records (
    write_off_id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    case_id                UUID         NOT NULL,
    credit_account_id      UUID         NOT NULL,
    obligor_party_id       UUID         NOT NULL,
    principal_written_off  NUMERIC(19,4) NOT NULL,
    interest_written_off   NUMERIC(19,4) NOT NULL,
    penalty_written_off    NUMERIC(19,4) NOT NULL,
    total_written_off      NUMERIC(19,4) NOT NULL,
    authorized_by          VARCHAR(100) NOT NULL,
    authorization_ref      VARCHAR(100) NOT NULL,
    write_off_date         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reason                 VARCHAR(20)  NOT NULL,
    bureau_reported        BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_write_off_records PRIMARY KEY (write_off_id),
    CONSTRAINT fk_write_off_records_case FOREIGN KEY (case_id)
        REFERENCES collections.collection_cases (case_id),
    CONSTRAINT chk_write_off_records_reason CHECK (reason IN
        ('UNRECOVERABLE','REGULATORY','LEGAL_SETTLEMENT'))
);

CREATE INDEX idx_write_off_records_case ON collections.write_off_records (case_id);
-- WO-03: an account cannot be written off twice
CREATE UNIQUE INDEX idx_write_off_records_account ON collections.write_off_records (credit_account_id);
