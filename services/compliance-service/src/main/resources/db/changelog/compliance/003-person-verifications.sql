--liquibase formatted sql
--changeset compliance:003-person-verifications author:system
--comment El veredicto de una persona: identidad y listas, uno por persona y no uno por crédito.

CREATE TABLE compliance.person_verifications (
    verification_id         UUID         PRIMARY KEY,
    party_id                UUID,
    prospect_id             UUID         NOT NULL,
    identity_status         VARCHAR(20)  NOT NULL,
    screening_status        VARCHAR(20)  NOT NULL,
    screened_list_versions  VARCHAR(1000),
    screened_at             TIMESTAMPTZ,
    decided_by              VARCHAR(120),
    decided_at              TIMESTAMPTZ,
    decision_notes          VARCHAR(1000),
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,

    CONSTRAINT chk_pv_identity  CHECK (identity_status  IN ('PENDING','IN_REVIEW','VERIFIED','REJECTED')),
    CONSTRAINT chk_pv_screening CHECK (screening_status IN ('PENDING','CLEARED','POTENTIAL_MATCH','CONFIRMED_MATCH')),

    -- Un veredicto de listas adjudicado sin autor ni argumento no sustenta nada ante una revisión —
    -- y el argumento es lo valioso: «nació en 1985, el de la lista en 1962».
    CONSTRAINT chk_pv_adjudicado CHECK (
        decided_at IS NULL
        OR (decided_by IS NOT NULL AND decision_notes IS NOT NULL))
);

-- Una persona se verifica UNA vez. Es el invariante que distingue este modelo del anterior, donde
-- el veredicto vivía en la colocación y la misma persona podía tener dos juicios distintos según
-- por dónde hubiera entrado.
CREATE UNIQUE INDEX uq_person_verification_prospect
    ON compliance.person_verifications (prospect_id);

CREATE UNIQUE INDEX uq_person_verification_party
    ON compliance.person_verifications (party_id)
    WHERE party_id IS NOT NULL;

-- La cola de cumplimiento: lo que espera adjudicación, lo más viejo primero.
CREATE INDEX idx_pv_pendientes
    ON compliance.person_verifications (screening_status, created_at)
    WHERE screening_status = 'POTENTIAL_MATCH';

COMMENT ON COLUMN compliance.person_verifications.screened_list_versions IS
    'Contra qué versiones de lista se corrió. Las listas cambian: alguien despejado hace seis meses '
    'puede estar hoy, y sin esto el veredicto no se puede auditar ni re-correr.';
