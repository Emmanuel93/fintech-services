--liquibase formatted sql
--changeset closing:007-create-seals
--comment El sello: el hecho auditable contra el que concilian contabilidad y bancos.

CREATE TABLE closing.close_seals (
    seal_id        UUID          NOT NULL DEFAULT gen_random_uuid(),
    business_date  DATE          NOT NULL,
    phase          VARCHAR(24)   NOT NULL,
    scope_key      VARCHAR(60)   NOT NULL DEFAULT 'ALL',
    unit_count     INTEGER       NOT NULL,

    -- Cifras de control. Es LO QUE contabilidad y bancos concilian contra el cierre; sin ellas el
    -- sello sería una marca de tiempo sin poder probatorio.
    total_principal NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_interest  NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_penalty   NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_debt      NUMERIC(19,4) NOT NULL DEFAULT 0,
    breakdown       JSONB,                       -- por sucursal / producto

    content_hash   VARCHAR(64)   NOT NULL,
    sealed_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_close_seals PRIMARY KEY (seal_id),
    -- Un sello por fecha, fase y alcance. Sellar dos veces el mismo día es el error que hace que
    -- dos reportes del mismo cierre no coincidan.
    CONSTRAINT uq_close_seal UNIQUE (business_date, phase, scope_key)
);

CREATE INDEX idx_seals_date ON closing.close_seals (business_date DESC);
