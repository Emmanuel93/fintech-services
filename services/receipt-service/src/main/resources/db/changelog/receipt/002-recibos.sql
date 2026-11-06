--liquibase formatted sql
--changeset receipt:002-recibos author:system
--comment The payment receipts of a distributor's clients: one per (line, cycle, client), with its PDF next to it.

CREATE TABLE receipt.receipts (
    receipt_id            UUID          NOT NULL,
    credit_account_id     UUID          NOT NULL,
    cycle_number          INTEGER       NOT NULL,
    beneficiary_party_id  UUID          NOT NULL,
    distributor_party_id  UUID,
    run_id                UUID,
    period_start          DATE          NOT NULL,
    cutoff_date           DATE          NOT NULL,
    payment_due_date      DATE          NOT NULL,
    status                VARCHAR(12)   NOT NULL DEFAULT 'PENDING',
    attempts              SMALLINT      NOT NULL DEFAULT 0,
    last_error            VARCHAR(500),
    source_event_id       VARCHAR(100),
    -- What the cutoff said: a retry generates the same without receiving the event again.
    facts                 JSONB         NOT NULL,
    -- What the receipt was printed from: a regeneration prints the same figures in the current design.
    snapshot              JSONB,
    folio                 VARCHAR(40),
    client_name           VARCHAR(200),
    client_type           VARCHAR(20),
    live_loans            INTEGER,
    -- What the client pays this fortnight, without his distributor's discount.
    total_due             NUMERIC(19,2),
    payment_reference     BIGINT,
    clabe                 VARCHAR(18),
    template_version      VARCHAR(80),
    document_bytes        BIGINT,
    generated_by          VARCHAR(60),
    generated_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_receipts PRIMARY KEY (receipt_id),
    -- The natural key: the same cutoff delivered twice finds the same receipt.
    CONSTRAINT uq_receipt_cycle_client UNIQUE (credit_account_id, cycle_number, beneficiary_party_id),
    CONSTRAINT chk_receipt_status CHECK (status IN ('PENDING','GENERATING','GENERATED','FAILED'))
);
CREATE INDEX idx_receipts_client      ON receipt.receipts (beneficiary_party_id, cutoff_date DESC);
CREATE INDEX idx_receipts_distributor ON receipt.receipts (distributor_party_id, cutoff_date DESC);
CREATE INDEX idx_receipts_retry       ON receipt.receipts (updated_at) WHERE status IN ('PENDING','GENERATING','FAILED');

CREATE TABLE receipt.receipt_documents (
    receipt_id    UUID         NOT NULL,
    content       BYTEA        NOT NULL,
    content_type  VARCHAR(60)  NOT NULL,
    size_bytes    INTEGER      NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_receipt_documents PRIMARY KEY (receipt_id),
    CONSTRAINT fk_receipt_document FOREIGN KEY (receipt_id) REFERENCES receipt.receipts (receipt_id) ON DELETE CASCADE
);

COMMENT ON TABLE receipt.receipts IS
    'Recibo de pago de cada cliente de una distribuidora por estado de cuenta: lo que paga sin el descuento de ella, cómo pagarlo y con quién es la obligación';
