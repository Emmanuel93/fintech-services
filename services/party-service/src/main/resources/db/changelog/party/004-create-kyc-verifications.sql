CREATE TABLE party.kyc_verifications
(
    verification_id     UUID         NOT NULL,
    party_id            UUID         NOT NULL,
    document_type       VARCHAR(30)  NOT NULL,
    verification_status VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    verified_by         VARCHAR(100),
    verified_at         TIMESTAMPTZ,
    rejection_reason    VARCHAR(300),
    document_ref        VARCHAR(500),
    expires_at          DATE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_kyc_verifications PRIMARY KEY (verification_id),
    CONSTRAINT fk_kyc_party         FOREIGN KEY (party_id) REFERENCES party.parties (party_id),
    CONSTRAINT ck_kyc_doc_type      CHECK (document_type IN ('INE', 'PASSPORT', 'RFC', 'CURP', 'ACTA_CONSTITUTIVA', 'PODER_NOTARIAL')),
    CONSTRAINT ck_kyc_status        CHECK (verification_status IN ('PENDING', 'IN_PROGRESS', 'VERIFIED', 'REJECTED', 'EXPIRED'))
);

CREATE INDEX idx_kyc_party_id        ON party.kyc_verifications (party_id);
CREATE INDEX idx_kyc_status          ON party.kyc_verifications (verification_status);
