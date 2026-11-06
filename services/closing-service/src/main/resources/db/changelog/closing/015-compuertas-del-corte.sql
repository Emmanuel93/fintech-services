--liquibase formatted sql
--changeset closing:015-compuertas-del-corte
--comment El corte de D corre cuando cartera cerró D y sella el saldo al cierre de D, no el del momento.

-- Los días que cartera cerró (credit-portfolio.day-closed). El corte de D sólo se planifica con este
-- renglón: sin él, el devengo de D o la mora pueden no estar todavía. Los días se planifican en orden.
CREATE TABLE IF NOT EXISTS closing.portfolio_day_closes (
    business_date            DATE PRIMARY KEY,
    delinquency_as_of        DATE,
    accounts                 INTEGER,
    errors                   INTEGER,
    received_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    waiting_for              TEXT,
    cutoff_planned_at        TIMESTAMPTZ,
    -- El aviso closing.cutoff-run-completed salió (el broker lo confirmó). Cargos no devenga D+1 hasta
    -- recibirlo; si no salió, la reanudación lo vuelve a mandar.
    completion_announced_at  TIMESTAMPTZ
);

-- Movimientos de saldo con su fecha valor. El saldo de la proyección es el de ahora; el corte de D se
-- sella con el de ahora menos lo que pertenece a días posteriores a D. Así un pago de las 00:07 de D+1
-- no entra al corte de D aunque el corte corra después de recibirlo.
CREATE TABLE IF NOT EXISTS closing.balance_movements (
    credit_account_id UUID           NOT NULL,
    balance_version   BIGINT         NOT NULL,
    value_date        DATE           NOT NULL,
    principal_delta   NUMERIC(19, 4) NOT NULL,
    debt_delta        NUMERIC(19, 4) NOT NULL,
    recorded_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (credit_account_id, balance_version)
);
CREATE INDEX IF NOT EXISTS idx_balance_movements_account_value
    ON closing.balance_movements (credit_account_id, value_date);
CREATE INDEX IF NOT EXISTS idx_balance_movements_value
    ON closing.balance_movements (value_date);

-- Desde qué día de negocio la cuenta está liquidada, castigada o cerrada. Una cuenta que se liquida el
-- 11 sí corta el 10: el filtro mira esta fecha, no el estado de hoy.
ALTER TABLE closing.account_close_profiles ADD COLUMN IF NOT EXISTS terminal_since DATE;
