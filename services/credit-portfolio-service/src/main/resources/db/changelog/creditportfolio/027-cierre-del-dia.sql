--liquibase formatted sql
--changeset credit-portfolio:027-cierre-del-dia
--comment El cierre del día corre después de medianoche: fecha valor en el libro y bitácora de cierres.

-- Fecha valor: el día de negocio al que pertenece el movimiento. El devengo del día D se aplica a las
-- 00:05 de D+1; con sólo applied_at, el interés del día de corte caería en el periodo siguiente.
ALTER TABLE credit_portfolio.balance_events ADD COLUMN IF NOT EXISTS value_date DATE;

UPDATE credit_portfolio.balance_events
   SET value_date = (applied_at AT TIME ZONE 'America/Mexico_City')::date
 WHERE value_date IS NULL;

ALTER TABLE credit_portfolio.balance_events ALTER COLUMN value_date SET NOT NULL;

-- El estado de cuenta lee por fecha valor; el índice por applied_at de la 026 ya no lo usa nadie.
DROP INDEX IF EXISTS credit_portfolio.idx_balance_events_account_applied;
CREATE INDEX IF NOT EXISTS idx_balance_events_account_value
    ON credit_portfolio.balance_events (credit_account_id, value_date, applied_at);

-- Un renglón por día cerrado. Es la idempotencia del cierre (el aviso de cargos puede llegar dos
-- veces) y lo que mira el respaldo para saber si el día ya se cerró.
CREATE TABLE IF NOT EXISTS credit_portfolio.day_closes (
    business_date      DATE PRIMARY KEY,
    delinquency_as_of  DATE        NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    triggered_by       TEXT        NOT NULL,
    accounts           INTEGER,
    errors             INTEGER,
    last_error         TEXT,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ
);
