--liquibase formatted sql
--changeset banking:004-create-reconciliation
--comment Estado de cuenta, cruce, puente y sello. Movidas desde `closing` (BK-04).

-- **Por qué dejaron de vivir en el cierre.** Nacieron ahí porque no había servicio de tesorería y
-- la conciliación se dispara desde una fase del cierre. Pero el dueño del dato no es quien lo
-- dispara: lo que el banco reporta es de `banking`, igual que la cuenta de la que salió el dinero.
-- El cierre sigue disparando la fase y consume el sello como cifra de control — no como dueño.
CREATE TABLE banking.bank_statement_lines (
    line_id          UUID          NOT NULL DEFAULT gen_random_uuid(),
    bank_account_id  UUID          NOT NULL,
    business_date    DATE          NOT NULL,
    value_date       DATE,
    direction        VARCHAR(6)    NOT NULL,      -- CREDIT (abono) | DEBIT (cargo)
    amount           NUMERIC(19,4) NOT NULL,
    -- Lo que permite el cruce determinista: clave de rastreo SPEI, referencia numérica, concepto.
    tracking_key     VARCHAR(60),
    reference        VARCHAR(120),
    counterparty     VARCHAR(160),
    counterparty_account VARCHAR(40),
    raw_payload      JSONB,
    -- Idempotencia de la ingesta: el mismo movimiento reingerido no se duplica.
    external_id      VARCHAR(120)  NOT NULL,
    ingested_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    match_status     VARCHAR(12)   NOT NULL DEFAULT 'UNMATCHED',

    CONSTRAINT pk_bank_statement_lines PRIMARY KEY (line_id),
    CONSTRAINT uq_bank_line_external UNIQUE (bank_account_id, external_id),
    CONSTRAINT chk_bank_line_direction CHECK (direction IN ('CREDIT','DEBIT')),
    CONSTRAINT chk_bank_line_amount CHECK (amount > 0),
    CONSTRAINT chk_bank_line_match CHECK (match_status IN ('UNMATCHED','MATCHED','SUSPENSE','IGNORED')),
    CONSTRAINT fk_bank_line_account FOREIGN KEY (bank_account_id)
        REFERENCES banking.bank_accounts (bank_account_id)
);

CREATE INDEX idx_bank_lines_unmatched ON banking.bank_statement_lines (bank_account_id, business_date)
    WHERE match_status = 'UNMATCHED';
CREATE INDEX idx_bank_lines_tracking  ON banking.bank_statement_lines (tracking_key)
    WHERE tracking_key IS NOT NULL;
CREATE INDEX idx_bank_lines_date      ON banking.bank_statement_lines (business_date);

-- El cruce entre lo que dice el banco y lo que dice la plataforma.
CREATE TABLE banking.bank_matches (
    match_id        UUID          NOT NULL DEFAULT gen_random_uuid(),
    line_id         UUID          NOT NULL,
    -- El hecho interno con el que cuadró: una orden de pago, un desembolso, una póliza.
    internal_type   VARCHAR(24)   NOT NULL,
    internal_ref    VARCHAR(120)  NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    -- Cómo se cruzó. Guardarlo no es cosmético: una conciliación que no puede explicar POR QUÉ
    -- cuadró dos importes no es auditable, y la heurística hay que poder revisarla después.
    method          VARCHAR(16)   NOT NULL,
    confidence      NUMERIC(5,4),
    matched_by      VARCHAR(80),
    matched_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_bank_matches PRIMARY KEY (match_id),
    -- Un movimiento bancario se cruza una sola vez.
    CONSTRAINT uq_bank_match_line UNIQUE (line_id),
    CONSTRAINT chk_bank_match_method CHECK (method IN ('DETERMINISTIC','HEURISTIC','MANUAL')),
    CONSTRAINT chk_bank_match_type CHECK (internal_type IN
        ('PAYMENT_ORDER','DISBURSEMENT_ORDER','STP_ORDER','JOURNAL_ENTRY','MANUAL_ADJUSTMENT')),
    CONSTRAINT fk_bank_match_line FOREIGN KEY (line_id)
        REFERENCES banking.bank_statement_lines (line_id) ON DELETE CASCADE
);

-- Lo no identificado. Sale del cierre del día como PARTIDA EN CONCILIACIÓN, no desaparece: es la
-- diferencia entre una conciliación y un reporte de diferencias.
CREATE TABLE banking.suspense_entries (
    suspense_id     UUID          NOT NULL DEFAULT gen_random_uuid(),
    line_id         UUID          NOT NULL,
    bank_account_id UUID          NOT NULL,
    business_date   DATE          NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    direction       VARCHAR(6)    NOT NULL,
    reason          VARCHAR(200)  NOT NULL,
    status          VARCHAR(12)   NOT NULL DEFAULT 'OPEN',
    resolved_at     TIMESTAMPTZ,
    resolved_by     VARCHAR(80),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_suspense_entries PRIMARY KEY (suspense_id),
    CONSTRAINT uq_suspense_line UNIQUE (line_id),
    CONSTRAINT chk_suspense_status CHECK (status IN ('OPEN','RESOLVED','WRITTEN_OFF')),
    CONSTRAINT fk_suspense_line FOREIGN KEY (line_id)
        REFERENCES banking.bank_statement_lines (line_id) ON DELETE CASCADE
);

CREATE INDEX idx_suspense_open ON banking.suspense_entries (bank_account_id, business_date)
    WHERE status = 'OPEN';

-- El sello bancario del día/mes. La ecuación que tiene que cuadrar:
--
--     saldo de la cuenta contable  +  partidas en conciliación  ==  saldo del estado de cuenta
--
-- Si no cuadra, no se sella y se levanta un hallazgo LEDGER_VS_BANK.
CREATE TABLE banking.bank_close_seals (
    seal_id          UUID          NOT NULL DEFAULT gen_random_uuid(),
    bank_account_id  UUID          NOT NULL,
    business_date    DATE          NOT NULL,
    period_type      VARCHAR(8)    NOT NULL,     -- DAILY | MONTHLY
    ledger_balance   NUMERIC(19,4) NOT NULL,
    bank_balance     NUMERIC(19,4) NOT NULL,
    suspense_total   NUMERIC(19,4) NOT NULL DEFAULT 0,
    difference       NUMERIC(19,4) NOT NULL,
    line_count       INTEGER       NOT NULL DEFAULT 0,
    matched_count    INTEGER       NOT NULL DEFAULT 0,
    sealed_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_bank_close_seals PRIMARY KEY (seal_id),
    CONSTRAINT uq_bank_seal UNIQUE (bank_account_id, business_date, period_type),
    CONSTRAINT chk_bank_seal_period CHECK (period_type IN ('DAILY','MONTHLY')),
    CONSTRAINT fk_bank_seal_account FOREIGN KEY (bank_account_id)
        REFERENCES banking.bank_accounts (bank_account_id)
);
