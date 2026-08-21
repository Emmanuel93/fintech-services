CREATE TABLE party.consent_records
(
    consent_id        UUID         NOT NULL,
    party_id          UUID         NOT NULL,
    consent_type      VARCHAR(50)  NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'GRANTED',
    granted_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    revocation_reason VARCHAR(300),
    document_ref      VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_consent_records   PRIMARY KEY (consent_id),
    CONSTRAINT fk_consent_party     FOREIGN KEY (party_id) REFERENCES party.parties (party_id),
    CONSTRAINT ck_consent_type      CHECK (consent_type IN ('CREDIT_BUREAU', 'MARKETING', 'PRIVACY_POLICY', 'DATA_PROCESSING', 'ARCO_CANCELLATION')),
    CONSTRAINT ck_consent_status    CHECK (status IN ('GRANTED', 'REVOKED', 'EXPIRED'))
);

CREATE INDEX idx_consent_party_id   ON party.consent_records (party_id);
CREATE INDEX idx_consent_type       ON party.consent_records (party_id, consent_type, status);
