--liquibase formatted sql
--changeset closing:016-corte-retenido
--comment El corte retiene las cuentas que cargos dejó fuera del cierre del día, y las suelta cuando devengan.

-- Las cuentas que no devengaron el día (cuarentena o retenidas en cargos): su corte espera a su interés.
ALTER TABLE closing.portfolio_day_closes
    ADD COLUMN IF NOT EXISTS held_accounts UUID[] NOT NULL DEFAULT '{}';

ALTER TABLE closing.close_units DROP CONSTRAINT IF EXISTS chk_unit_status;
ALTER TABLE closing.close_units ADD CONSTRAINT chk_unit_status
    CHECK (status IN ('PENDING', 'CLAIMED', 'DONE', 'FAILED', 'SKIPPED', 'HELD'));

-- Cuentas que cartera ya soltó (credit-portfolio.account-day-closed). Si llega antes de planificar el corte,
-- el corte nace con la cuenta pendiente y no retenida.
CREATE TABLE IF NOT EXISTS closing.held_releases (
    business_date     DATE        NOT NULL,
    credit_account_id UUID        NOT NULL,
    released_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_date, credit_account_id)
);
CREATE INDEX IF NOT EXISTS idx_close_units_held
    ON closing.close_units (run_id, credit_account_id) WHERE status = 'HELD';
