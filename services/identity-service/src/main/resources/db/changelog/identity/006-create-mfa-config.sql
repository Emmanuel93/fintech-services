-- liquibase formatted sql

-- changeset identity:006 author:fintech
-- comment: TOTP-based 2FA configuration per party. Secret stored in base32.
CREATE TABLE identity.mfa_config (
    id           UUID         PRIMARY KEY,
    party_id     UUID         NOT NULL UNIQUE,
    totp_secret  VARCHAR(64)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT false,
    enrolled_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_mfa_config_party_id ON identity.mfa_config (party_id);
