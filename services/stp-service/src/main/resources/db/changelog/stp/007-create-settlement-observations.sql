--liquibase formatted sql

--changeset stp-service:007-create-settlement-observations
-- Lo que STP nos dijo cuando le preguntamos. Evidencia cruda, append-only (SO-01).
-- Sustituye a los webhooks del legado: la plataforma no expone nada a internet.
CREATE TABLE stp.settlement_observations (
    observation_id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id           UUID         NOT NULL,
    tracking_key         VARCHAR(30)  NOT NULL,
    observed_status      VARCHAR(10)  NOT NULL,

    -- NOT NULL con default vacío a propósito: en Postgres los NULL no colisionan en un UNIQUE, y
    -- una orden devuelta puede llegar sin tsLiquidacion. Con NULL, SO-02 no deduplicaría.
    observed_at_source   VARCHAR(30)  NOT NULL DEFAULT '',

    return_cause_code    VARCHAR(10),
    cep_url              VARCHAR(500),
    cep_beneficiary_name VARCHAR(150),
    provider_signature   TEXT,
    signature_ok         BOOLEAN,
    -- TEXT y no JSONB: la entidad lo mapea como String, y Postgres reporta jsonb como
    -- Types#OTHER, lo que hace fallar el arranque con ddl-auto=validate. Es evidencia cruda para
    -- auditoría, no algo que se consulte por sus campos.
    raw_payload          TEXT         NOT NULL,
    observed_via         VARCHAR(30)  NOT NULL
        CONSTRAINT settlement_observations_via_chk
            CHECK (observed_via IN ('POLL_RECONCILIATION', 'POLL_ORDER', 'EOD_BATCH', 'MANUAL')),
    status               VARCHAR(30)  NOT NULL
        CONSTRAINT settlement_observations_status_chk
            CHECK (status IN ('APPLIED', 'DUPLICATE', 'UNMATCHED', 'SIGNATURE_INVALID',
                              'SIGNATURE_UNVERIFIED', 'FAILED')),
    detail               VARCHAR(1000),
    observed_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT settlement_observations_pk PRIMARY KEY (observation_id),
    -- SO-02: el poller relee lo mismo cada N minutos por diseño.
    CONSTRAINT settlement_observations_uq
        UNIQUE (company_id, tracking_key, observed_status, observed_at_source)
);

CREATE INDEX settlement_observations_tracking_idx
    ON stp.settlement_observations (company_id, tracking_key, observed_at DESC);
