--liquibase formatted sql
--changeset statement:002-estados-de-cuenta
--comment Un estado de cuenta por cuenta y ciclo, con su PDF y el avance por corrida de corte.

CREATE TABLE statement.statements (
    statement_id       UUID          NOT NULL,
    credit_account_id  UUID          NOT NULL,
    cycle_number       INTEGER       NOT NULL,
    run_id             UUID,
    obligor_party_id   UUID,
    holder_kind        VARCHAR(20)   NOT NULL,
    product_type       VARCHAR(40)   NOT NULL,
    product_behavior   VARCHAR(20)   NOT NULL,
    layout             VARCHAR(20)   NOT NULL,
    period_start       DATE          NOT NULL,
    cutoff_date        DATE          NOT NULL,
    payment_due_date   DATE          NOT NULL,
    -- Las cifras del corte sellado: el documento las presenta, no las recalcula.
    balance_at_cutoff  NUMERIC(19,2),
    amount_due         NUMERIC(19,2),
    minimum_payment    NUMERIC(19,2),
    contract_number    VARCHAR(40),
    status             VARCHAR(12)   NOT NULL DEFAULT 'PENDING',
    attempts           SMALLINT      NOT NULL DEFAULT 0,
    last_error         VARCHAR(500),
    source_event_id    VARCHAR(100),
    -- Lo que dijo el corte. Con esto el reintento genera lo mismo sin volver a recibir el evento.
    facts              JSONB         NOT NULL,
    -- La vista de cartera con la que se armó: si alguien pregunta por qué el documento dice lo que dice.
    snapshot           JSONB,
    template_version   VARCHAR(80),
    document_bytes     BIGINT,
    generated_by       VARCHAR(60),
    generated_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_statements PRIMARY KEY (statement_id),
    -- La llave natural: el mismo corte entregado dos veces encuentra el mismo documento.
    CONSTRAINT uq_statement_cycle UNIQUE (credit_account_id, cycle_number),
    CONSTRAINT chk_statement_status CHECK (status IN ('PENDING','GENERATING','GENERATED','FAILED')),
    CONSTRAINT chk_statement_layout CHECK (layout IN ('INSTALLMENT','REVOLVING','DISTRIBUTOR')),
    CONSTRAINT chk_statement_holder CHECK (holder_kind IN ('CUSTOMER','DISTRIBUTOR'))
);

-- El avance de una corrida, por producto y estado.
CREATE INDEX idx_statements_run   ON statement.statements (run_id, status);
-- «Mis estados de cuenta», del más reciente al más viejo.
CREATE INDEX idx_statements_party ON statement.statements (obligor_party_id, cutoff_date DESC);
-- El reintento: fallidos y tomados por un pod que dejó de dar señales.
CREATE INDEX idx_statements_retry ON statement.statements (updated_at)
    WHERE status IN ('PENDING','GENERATING','FAILED');

-- El PDF. En la base como el resto de los documentos de la plataforma mientras no haya almacén de
-- objetos; el puerto StatementDocumentStore es donde entra uno sin tocar la generación.
CREATE TABLE statement.statement_documents (
    statement_id  UUID         NOT NULL,
    content       BYTEA        NOT NULL,
    content_type  VARCHAR(60)  NOT NULL,
    size_bytes    INTEGER      NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_statement_documents PRIMARY KEY (statement_id),
    CONSTRAINT fk_document_statement FOREIGN KEY (statement_id)
        REFERENCES statement.statements (statement_id) ON DELETE CASCADE
);

-- Las corridas de corte vistas desde aquí: cuántos documentos se esperan y cómo terminó el corte.
CREATE TABLE statement.statement_batches (
    run_id               UUID         NOT NULL,
    business_date        DATE         NOT NULL,
    expected             INTEGER      NOT NULL DEFAULT 0,
    breakdown            JSONB,
    cutoff_status        VARCHAR(12),
    cutoff_done          INTEGER,
    cutoff_failed        INTEGER,
    planned_at           TIMESTAMPTZ,
    cutoff_completed_at  TIMESTAMPTZ,

    CONSTRAINT pk_statement_batches PRIMARY KEY (run_id)
);

CREATE INDEX idx_statement_batches_date ON statement.statement_batches (business_date);
