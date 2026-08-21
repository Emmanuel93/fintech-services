--liquibase formatted sql

--changeset disbursement-service:004-create-disbursement-orders
-- Raíz de agregado. NO hay una sola columna del dominio de crédito: la procedencia viaja en
-- source_system / source_reference / source_metadata, opacos para el núcleo. Eso es lo que permite
-- vender este servicio por separado, y es verificable leyendo esta tabla.
CREATE TABLE disbursement.disbursement_orders (
    disbursement_id          UUID          NOT NULL,
    company_id               UUID          NOT NULL,
    -- Bloqueo optimista. El job de despacho y el resultado del conector escriben la misma fila con
    -- milisegundos de diferencia; sin esto, la escritura que llega tarde con datos viejos borraría
    -- el proveedor y el contador de intentos.
    version                  BIGINT        NOT NULL DEFAULT 0,

    source_system            VARCHAR(60)   NOT NULL,
    source_type              VARCHAR(40)   NOT NULL
        CONSTRAINT disbursement_orders_source_type_chk
            CHECK (source_type IN ('DISPOSITION', 'WITHDRAWAL', 'SURPLUS_RETURN',
                                   'ACCOUNT_VERIFICATION', 'API', 'MANUAL')),
    source_reference         VARCHAR(120),
    source_event_id          VARCHAR(120)  NOT NULL,
    source_metadata          JSONB         NOT NULL DEFAULT '{}'::jsonb,

    beneficiary_name         VARCHAR(150)  NOT NULL,
    beneficiary_account      VARCHAR(20)   NOT NULL,
    beneficiary_account_type VARCHAR(4)    NOT NULL,
    beneficiary_tax_id       VARCHAR(18),
    beneficiary_institution  INTEGER,

    amount                   NUMERIC(19,2) NOT NULL
        CONSTRAINT disbursement_orders_amount_pos_chk CHECK (amount > 0),
    currency                 VARCHAR(3)    NOT NULL,
    concept                  VARCHAR(40),
    numeric_reference        BIGINT,

    rail                     VARCHAR(20)   NOT NULL
        CONSTRAINT disbursement_orders_rail_chk CHECK (rail IN ('SPEI', 'CODI', 'INTERNAL')),
    provider                 VARCHAR(20),
    status                   VARCHAR(20)   NOT NULL
        CONSTRAINT disbursement_orders_status_chk
            CHECK (status IN ('REQUESTED', 'DISPATCHED', 'ACCEPTED', 'SETTLED',
                              'REJECTED', 'RETURNED', 'FAILED', 'CANCELLED')),
    external_ref             VARCHAR(60),
    cep_url                  VARCHAR(500),
    failure_code             VARCHAR(60),
    failure_reason           VARCHAR(1000),
    attempt_count            INTEGER       NOT NULL DEFAULT 0,
    scheduled_for            TIMESTAMPTZ,
    correlation_id           VARCHAR(64),

    created_at               TIMESTAMPTZ   NOT NULL,
    dispatched_at            TIMESTAMPTZ,
    settled_at               TIMESTAMPTZ,
    terminated_at            TIMESTAMPTZ,

    CONSTRAINT disbursement_orders_pk PRIMARY KEY (disbursement_id),
    -- DB-02: la idempotencia la impone la base de datos, no una comprobación en memoria que
    -- pierde la carrera entre dos réplicas consumiendo la misma partición.
    CONSTRAINT disbursement_orders_source_uq
        UNIQUE (source_system, source_type, source_event_id)
);

-- Es exactamente el SELECT ... FOR UPDATE SKIP LOCKED del job de despacho.
CREATE INDEX disbursement_orders_due_idx
    ON disbursement.disbursement_orders (scheduled_for NULLS FIRST, created_at)
    WHERE status = 'REQUESTED';

CREATE INDEX disbursement_orders_company_created_idx
    ON disbursement.disbursement_orders (company_id, created_at DESC);

-- Para reconciliar contra el conector cuando alguien pregunta por una clave del proveedor.
CREATE INDEX disbursement_orders_external_ref_idx
    ON disbursement.disbursement_orders (external_ref)
    WHERE external_ref IS NOT NULL;
