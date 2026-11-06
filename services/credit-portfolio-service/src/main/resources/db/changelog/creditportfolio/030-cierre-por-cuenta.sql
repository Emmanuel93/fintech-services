--liquibase formatted sql
--changeset credit-portfolio:030-cierre-por-cuenta
--comment Cargos cierra por cuenta: el aviso del día trae las cuentas que quedaron fuera, y cada una se suelta aparte.

-- Las cuentas que no devengaron en el cierre del día (cuarentena o retenidas). Cartera cuadra sin ellas y se
-- las pasa a closing, que retiene su corte.
ALTER TABLE credit_portfolio.charges_day_accruals
    ADD COLUMN IF NOT EXISTS held_accounts UUID[] NOT NULL DEFAULT '{}';

-- Una cuenta que devengó tarde: cartera cuadra sus cargos de ese día y avisa a closing que ya puede cortarla.
CREATE TABLE IF NOT EXISTS credit_portfolio.account_day_releases (
    business_date      DATE           NOT NULL,
    credit_account_id  UUID           NOT NULL,
    ordinary_charges   INTEGER        NOT NULL,
    ordinary_amount    NUMERIC(20, 4) NOT NULL,
    moratorium_charges INTEGER        NOT NULL,
    moratorium_amount  NUMERIC(20, 4) NOT NULL,
    status             TEXT           NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RELEASED')),
    waiting_for        TEXT,
    received_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    released_at        TIMESTAMPTZ,
    PRIMARY KEY (business_date, credit_account_id)
);
CREATE INDEX IF NOT EXISTS idx_account_day_releases_pending
    ON credit_portfolio.account_day_releases (business_date) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_balance_events_day_close_account
    ON credit_portfolio.balance_events (credit_account_id, day_close_date) WHERE day_close_date IS NOT NULL;
