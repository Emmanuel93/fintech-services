--liquibase formatted sql
--changeset closing:006-create-runs-units
--comment Motor de corridas. El reparto se coordina con el candado de Redis, NO con locks de BD.

CREATE TABLE closing.close_runs (
    run_id        UUID         NOT NULL DEFAULT gen_random_uuid(),
    business_date DATE         NOT NULL,
    phase         VARCHAR(24)  NOT NULL,
    scope_key     VARCHAR(60)  NOT NULL DEFAULT 'ALL',
    status        VARCHAR(12)  NOT NULL DEFAULT 'PLANNED',
    planned_units INTEGER      NOT NULL DEFAULT 0,
    done_units    INTEGER      NOT NULL DEFAULT 0,
    failed_units  INTEGER      NOT NULL DEFAULT 0,
    skipped_units INTEGER      NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ,
    sealed_at     TIMESTAMPTZ,
    last_error    VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_close_runs PRIMARY KEY (run_id),
    -- El candado de Redis decide quién planifica. Esta restricción es la SEGUNDA red: si el
    -- candado fallara, la base sigue impidiendo dos corridas de la misma fecha y fase.
    CONSTRAINT uq_close_run UNIQUE (business_date, phase, scope_key),
    CONSTRAINT chk_run_status CHECK (status IN ('PLANNED','RUNNING','SEALED','FAILED'))
);

CREATE INDEX idx_runs_date_phase ON closing.close_runs (business_date, phase);

-- LA UNIDAD DE TRABAJO: una cuenta/producto. Es la fila que los pods se reparten.
CREATE TABLE closing.close_units (
    unit_id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    run_id            UUID         NOT NULL,
    unit_key          VARCHAR(80)  NOT NULL,   -- normalmente el creditAccountId
    credit_account_id UUID,
    product_type      VARCHAR(40),
    status            VARCHAR(10)  NOT NULL DEFAULT 'PENDING',

    -- Arrendamiento. La verdad la tiene el TTL de Redis; estas columnas son lo consultable:
    -- el reaper devuelve a PENDING lo que esté CLAIMED con el arrendamiento vencido.
    lease_owner       VARCHAR(60),
    lease_expires_at  TIMESTAMPTZ,
    -- Rechaza al escritor cuyo candado ya expiró (§9.1). Un candado con TTL puede vencer con el
    -- trabajo vivo; el número monótono es lo que distingue al titular actual del anterior.
    fencing_token     BIGINT,

    attempts          SMALLINT     NOT NULL DEFAULT 0,
    last_error        VARCHAR(500),
    -- La política vigente el día D, congelada. Una re-corrida del 15 usa la del 15, no la de hoy.
    policy_snapshot   JSONB,
    balance_version   BIGINT,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_close_units PRIMARY KEY (unit_id),
    CONSTRAINT uq_close_unit UNIQUE (run_id, unit_key),
    CONSTRAINT chk_unit_status CHECK (status IN ('PENDING','CLAIMED','DONE','FAILED','SKIPPED')),
    CONSTRAINT fk_unit_run FOREIGN KEY (run_id) REFERENCES closing.close_runs (run_id) ON DELETE CASCADE
);

-- El índice de la lectura de candidatas. SELECT normal: sin FOR UPDATE, sin SKIP LOCKED.
CREATE INDEX idx_units_pending ON closing.close_units (run_id, unit_id) WHERE status = 'PENDING';
-- El reaper cruza esta columna contra la ausencia de la llave en Redis.
CREATE INDEX idx_units_lease   ON closing.close_units (lease_expires_at) WHERE status = 'CLAIMED';
CREATE INDEX idx_units_failed  ON closing.close_units (run_id) WHERE status = 'FAILED';
