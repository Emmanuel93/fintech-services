--liquibase formatted sql
--changeset beneficiary:002-create-placements author:system

CREATE TABLE beneficiary.placements (
    placement_id                  UUID          NOT NULL,
    distributor_party_id          UUID          NOT NULL,
    distributor_credit_account_id UUID          NOT NULL,

    -- Alta previa: los tres campos de la pantalla 18. Nada más se pide para invitar.
    beneficiary_full_name         VARCHAR(200)  NOT NULL,
    beneficiary_phone             VARCHAR(10)   NOT NULL,
    beneficiary_relationship      VARCHAR(300),

    -- Expediente real. Nulos hasta KYC_COMPLETED: el prospecto no se crea al invitar, porque
    -- crearlo dispararía el prefetch de buró de scoring antes de que ella autorizara nada.
    beneficiary_prospect_id       UUID,
    beneficiary_party_id          UUID,
    beneficiary_clabe             VARCHAR(18),

    amount                        NUMERIC(15,2) NOT NULL,
    term_fortnights               INTEGER       NOT NULL,
    verification_mode             VARCHAR(30)   NOT NULL,

    status                        VARCHAR(20)   NOT NULL,
    status_reason                 VARCHAR(500),
    disposition_id                UUID,

    created_at                    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    decided_at                    TIMESTAMPTZ,
    disbursed_at                  TIMESTAMPTZ,
    version                       BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_placements PRIMARY KEY (placement_id),

    CONSTRAINT chk_placement_status CHECK (status IN (
        'INVITED', 'KYC_IN_PROGRESS', 'KYC_COMPLETED', 'BUREAU_READY',
        'APPROVED', 'DISBURSING', 'DISBURSED',
        'REJECTED', 'EXPIRED', 'CANCELLED', 'FAILED')),

    CONSTRAINT chk_placement_verification_mode CHECK (verification_mode IN ('SELF_SERVICE_LINK')),

    CONSTRAINT chk_placement_phone  CHECK (beneficiary_phone ~ '^[0-9]{10}$'),
    CONSTRAINT chk_placement_clabe  CHECK (beneficiary_clabe IS NULL OR beneficiary_clabe ~ '^[0-9]{18}$'),
    CONSTRAINT chk_placement_amount CHECK (amount >= 5000),
    CONSTRAINT chk_placement_term   CHECK (term_fortnights > 0),

    -- El expediente es indivisible: o están los tres datos o no está ninguno. Media beneficiaria
    -- —con Party pero sin CLABE, o con prospecto pero sin Party— es un estado que no existe en el
    -- dominio, y la base no debería poder representarlo.
    CONSTRAINT chk_placement_file_all_or_nothing CHECK (
        (beneficiary_prospect_id IS NULL AND beneficiary_party_id IS NULL AND beneficiary_clabe IS NULL)
     OR (beneficiary_prospect_id IS NOT NULL AND beneficiary_party_id IS NOT NULL AND beneficiary_clabe IS NOT NULL)),

    -- A partir de KYC_COMPLETED tiene que haber expediente. Sin esto, un bug de transición podría
    -- dejar una colocación aprobada sin saber a nombre de quién ni a qué cuenta depositar.
    CONSTRAINT chk_placement_file_required_after_kyc CHECK (
        status IN ('INVITED', 'KYC_IN_PROGRESS', 'EXPIRED', 'CANCELLED', 'FAILED')
     OR beneficiary_party_id IS NOT NULL),

    -- Nada se desembolsa sin disposición que lo respalde.
    CONSTRAINT chk_placement_disposition_required CHECK (
        status NOT IN ('DISBURSING', 'DISBURSED') OR disposition_id IS NOT NULL),

    -- FAILED siempre lleva motivo: es el único terminal que no es decisión de nadie.
    CONSTRAINT chk_placement_failed_has_reason CHECK (
        status <> 'FAILED' OR (status_reason IS NOT NULL AND status_reason <> ''))
);

-- La consulta que domina el servicio: la pestaña Colocaciones, con y sin filtro de estado.
CREATE INDEX idx_placements_distributor_created
    ON beneficiary.placements (distributor_party_id, created_at DESC);

CREATE INDEX idx_placements_distributor_status
    ON beneficiary.placements (distributor_party_id, status, created_at DESC);

-- La pestaña Beneficiarios busca por persona; sólo tiene sentido sobre las que ya tienen Party.
CREATE INDEX idx_placements_beneficiary_party
    ON beneficiary.placements (beneficiary_party_id)
    WHERE beneficiary_party_id IS NOT NULL;

-- El sweeper de vencimiento sólo mira las ligas vivas. Parcial para que no crezca con el histórico.
CREATE INDEX idx_placements_live_invites
    ON beneficiary.placements (created_at)
    WHERE status IN ('INVITED', 'KYC_IN_PROGRESS');

-- Los listeners de scoring y de disposiciones entran por estas dos llaves ajenas al agregado.
CREATE INDEX idx_placements_prospect
    ON beneficiary.placements (beneficiary_prospect_id)
    WHERE beneficiary_prospect_id IS NOT NULL;

CREATE UNIQUE INDEX uq_placements_disposition
    ON beneficiary.placements (disposition_id)
    WHERE disposition_id IS NOT NULL;
