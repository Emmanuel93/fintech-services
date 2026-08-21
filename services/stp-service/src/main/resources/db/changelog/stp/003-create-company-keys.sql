--liquibase formatted sql

--changeset stp-service:003-create-company-keys
-- Envelope encryption: el material privado se cifra con una DEK, y la DEK con una KEK que
-- NUNCA está en esta base de datos. Un dump no compromete ninguna llave.
CREATE TABLE stp.company_keys (
    key_id             UUID         NOT NULL,
    company_id         UUID         NOT NULL,
    alias              VARCHAR(80)  NOT NULL,
    purpose            VARCHAR(20)  NOT NULL DEFAULT 'SIGNING'
        CONSTRAINT company_keys_purpose_chk CHECK (purpose IN ('SIGNING', 'VERIFICATION')),
    algorithm          VARCHAR(30)  NOT NULL DEFAULT 'SHA256withRSA',
    key_size           INTEGER      NOT NULL DEFAULT 2048,

    -- Sólo SIGNING: material privado envuelto
    wrapped_dek        BYTEA,
    encrypted_material BYTEA,
    iv                 BYTEA,
    auth_tag           BYTEA,
    kek_id             VARCHAR(60),

    -- VERIFICATION: la pública de STP. SIGNING: la pública derivada de la nuestra, para el
    -- fingerprint y para que el stub de ambientes bajos pueda verificar lo que firmamos.
    public_key_spki    BYTEA,

    fingerprint_sha256 VARCHAR(64)  NOT NULL,
    valid_from         TIMESTAMPTZ  NOT NULL,
    valid_to           TIMESTAMPTZ  NOT NULL,
    status             VARCHAR(20)  NOT NULL
        CONSTRAINT company_keys_status_chk
            CHECK (status IN ('ACTIVE', 'ROTATING', 'RETIRED', 'REVOKED')),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(60)  NOT NULL,

    CONSTRAINT company_keys_pk PRIMARY KEY (key_id),
    CONSTRAINT company_keys_material_chk CHECK (
        (purpose = 'SIGNING'
             AND encrypted_material IS NOT NULL AND wrapped_dek IS NOT NULL
             AND iv IS NOT NULL AND auth_tag IS NOT NULL AND kek_id IS NOT NULL)
     OR (purpose = 'VERIFICATION' AND public_key_spki IS NOT NULL)),
    CONSTRAINT company_keys_validity_chk CHECK (valid_to > valid_from)
);

-- Una sola llave ACTIVE por empresa y propósito. Permite rotar con ROTATING sin ventana.
CREATE UNIQUE INDEX company_keys_active_uq
    ON stp.company_keys (company_id, purpose) WHERE status = 'ACTIVE';

CREATE INDEX company_keys_expiry_idx
    ON stp.company_keys (valid_to) WHERE status = 'ACTIVE';
