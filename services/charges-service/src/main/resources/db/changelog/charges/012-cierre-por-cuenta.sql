--liquibase formatted sql
--changeset charges:012-cierre-por-cuenta
--comment El devengo del cierre se reparte por cuenta: una unidad por cuenta y día, con cuarentena y retención.

-- Una cuenta que no puede devengar ya no detiene el día: tras sus intentos queda en cuarentena, con su
-- error, y el día cierra con las demás. Sus días siguientes nacen retenidos (HELD) hasta que se recupere:
-- el interés de una cuenta nunca se adelanta a uno que le falta.
CREATE TABLE IF NOT EXISTS charges.accrual_units (
    business_date      DATE        NOT NULL,
    credit_account_id  UUID        NOT NULL,
    schedule_id        UUID        NOT NULL,
    status             TEXT        NOT NULL
        CHECK (status IN ('PENDING', 'CLAIMED', 'DONE', 'FAILED', 'QUARANTINED', 'HELD')),
    attempts           INT         NOT NULL DEFAULT 0,
    fencing_token      BIGINT,
    lease_expires_at   TIMESTAMPTZ,
    dispatched_at      TIMESTAMPTZ,
    failed_at          TIMESTAMPTZ,
    last_error         TEXT,
    held_reason        TEXT,
    ordinary_charges   INT,
    ordinary_amount    NUMERIC(19, 4),
    moratorium_charges INT,
    moratorium_amount  NUMERIC(19, 4),
    completed_at       TIMESTAMPTZ,
    -- Terminó después de que el día ya se había avisado: se avisa aparte, cuenta por cuenta.
    late               BOOLEAN     NOT NULL DEFAULT false,
    late_announced_at  TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_date, credit_account_id)
);

CREATE INDEX IF NOT EXISTS idx_accrual_units_open
    ON charges.accrual_units (business_date, status) WHERE status IN ('PENDING', 'CLAIMED', 'FAILED');
CREATE INDEX IF NOT EXISTS idx_accrual_units_trouble
    ON charges.accrual_units (credit_account_id, business_date) WHERE status IN ('QUARANTINED', 'HELD');
CREATE INDEX IF NOT EXISTS idx_accrual_units_late
    ON charges.accrual_units (business_date) WHERE late AND late_announced_at IS NULL;

CREATE SEQUENCE IF NOT EXISTS charges.accrual_unit_fencing;

ALTER TABLE charges.accrual_days ADD COLUMN IF NOT EXISTS planned_units INT;
ALTER TABLE charges.accrual_days ADD COLUMN IF NOT EXISTS held_units INT;
