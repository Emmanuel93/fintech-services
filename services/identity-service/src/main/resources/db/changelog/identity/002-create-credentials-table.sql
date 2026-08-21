-- liquibase formatted sql

-- changeset identity:002 author:fintech
CREATE TABLE identity.credentials (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    party_id        UUID            NOT NULL,
    username        VARCHAR(255)    NOT NULL,
    credential_type VARCHAR(20)     NOT NULL,
    password_hash   VARCHAR(255),
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    failed_attempts INT             NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_credentials PRIMARY KEY (id),
    CONSTRAINT uq_credentials_username UNIQUE (username),
    CONSTRAINT chk_credential_type CHECK (credential_type IN ('NIP', 'PASSWORD')),
    CONSTRAINT chk_credential_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);

CREATE UNIQUE INDEX uix_credentials_party_type
    ON identity.credentials (party_id, credential_type);
