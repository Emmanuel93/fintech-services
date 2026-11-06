--liquibase formatted sql
--changeset credit-portfolio:028-compuertas-del-cierre
--comment El cierre del día en orden: aviso de cargos con cifras de control, cargos rechazados y espera.

-- Un día puede quedar esperando una compuerta (cargos no ha avisado, faltan intereses por aplicar, el
-- día anterior no ha cerrado). Queda escrito por qué.
ALTER TABLE credit_portfolio.day_closes DROP CONSTRAINT IF EXISTS day_closes_status_check;
ALTER TABLE credit_portfolio.day_closes
    ADD CONSTRAINT day_closes_status_check CHECK (status IN ('WAITING', 'RUNNING', 'DONE', 'FAILED'));
ALTER TABLE credit_portfolio.day_closes ADD COLUMN IF NOT EXISTS waiting_for TEXT;
ALTER TABLE credit_portfolio.day_closes ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;

-- Lo que cargos dice que devengó cada día (charges.day-accrued). Cartera no cierra el día hasta que
-- lo que aplicó, más lo que rechazó, cuadra con estas cifras.
CREATE TABLE IF NOT EXISTS credit_portfolio.charges_day_accruals (
    business_date      DATE PRIMARY KEY,
    ordinary_charges   INTEGER        NOT NULL,
    ordinary_amount    NUMERIC(20, 4) NOT NULL,
    moratorium_charges INTEGER        NOT NULL,
    moratorium_amount  NUMERIC(20, 4) NOT NULL,
    received_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Los cargos que cartera rechazó (cuenta castigada o cerrada). Antes sólo se avisaba a cargos; sin
-- este registro no había forma de cuadrar el día, ni de saber después qué se rechazó y por qué.
CREATE TABLE IF NOT EXISTS credit_portfolio.charge_rejections (
    source_event_id   TEXT PRIMARY KEY,
    credit_account_id UUID           NOT NULL,
    charge_type       TEXT           NOT NULL,
    amount            NUMERIC(20, 4) NOT NULL,
    value_date        DATE           NOT NULL,
    reason            TEXT           NOT NULL,
    rejected_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_charge_rejections_value_type
    ON credit_portfolio.charge_rejections (value_date, charge_type);

-- Las cifras de control cuentan los intereses del día por fecha valor y tipo.
CREATE INDEX IF NOT EXISTS idx_balance_events_value_type
    ON credit_portfolio.balance_events (value_date, event_type);
