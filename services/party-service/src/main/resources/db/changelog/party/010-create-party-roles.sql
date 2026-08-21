-- changeset fintech:010-create-party-roles
-- Roles comerciales/legales adicionales de un party (I-03): un party INDIVIDUAL/BUSINESS puede
-- ADEMÁS ser DISTRIBUTOR (coloca crédito B2B2C), GUARANTOR (avala) o BENEFICIARY. NO es un PartyType
-- nuevo (respeta I-01, partyType inmutable): es una capacidad aditiva. Un mismo party puede ser
-- cliente y distribuidor a la vez. Revocar CIERRA (active=false, revoked_at), no borra: queda historial.
CREATE TABLE party.party_roles (
    role_id     UUID         NOT NULL,
    party_id    UUID         NOT NULL,
    role_type   VARCHAR(30)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    granted_by  VARCHAR(120),
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at  TIMESTAMPTZ,
    CONSTRAINT pk_party_roles PRIMARY KEY (role_id),
    CONSTRAINT fk_party_roles_party FOREIGN KEY (party_id) REFERENCES party.parties (party_id)
);

-- A lo más UN rol activo del mismo tipo por party (índice único parcial sobre las filas vigentes).
CREATE UNIQUE INDEX uq_party_roles_active
    ON party.party_roles (party_id, role_type) WHERE active;

-- Listar los parties con un rol dado (p.ej. todos los distribuidores) sin seq scan.
CREATE INDEX idx_party_roles_role_active
    ON party.party_roles (role_type) WHERE active;
