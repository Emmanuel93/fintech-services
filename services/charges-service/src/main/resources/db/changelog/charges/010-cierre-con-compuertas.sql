--liquibase formatted sql
--changeset charges:010-cierre-con-compuertas
--comment El cierre del día en orden: bitácora por día y el aviso de cartera de que cerró.

-- Un renglón por día de negocio. Los días se cierran en orden y sin huecos: el siguiente pendiente es
-- el último DONE más uno. Guarda las cifras de control que viajan en charges.day-accrued, con las que
-- cartera comprueba que le llegó todo el interés antes de seguir.
CREATE TABLE IF NOT EXISTS charges.accrual_days (
    business_date        DATE PRIMARY KEY,
    status               TEXT        NOT NULL CHECK (status IN ('WAITING', 'RUNNING', 'DONE', 'FAILED')),
    triggered_by         TEXT        NOT NULL,
    attempts             INTEGER     NOT NULL DEFAULT 0,
    waiting_for          TEXT,
    ordinary_schedules   INTEGER,
    ordinary_errors      INTEGER,
    moratorium_schedules INTEGER,
    moratorium_errors    INTEGER,
    ordinary_charges     INTEGER,
    ordinary_amount      NUMERIC(20, 4),
    moratorium_charges   INTEGER,
    moratorium_amount    NUMERIC(20, 4),
    last_error           TEXT,
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    announced_at         TIMESTAMPTZ,
    republished_at       TIMESTAMPTZ
);

-- Los días que cartera ya cerró (credit-portfolio.day-closed). El día D+1 no se devenga hasta que
-- cartera cerró D: el moratorio de D+1 se cobra sobre la mora que esa corrida midió.
CREATE TABLE IF NOT EXISTS charges.portfolio_day_closes (
    business_date     DATE PRIMARY KEY,
    delinquency_as_of DATE,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Los cortes que closing terminó (closing.cutoff-run-completed). Con esto la cadena queda en serie: el
-- devengo de D+1 no arranca hasta que el corte de D terminó, aunque se estén recuperando días atrasados.
CREATE TABLE IF NOT EXISTS charges.cutoff_completions (
    business_date DATE PRIMARY KEY,
    status        TEXT        NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cifras de control y reenvío: los cargos de un día por tipo.
CREATE INDEX IF NOT EXISTS idx_charge_records_accrual_type
    ON charges.charge_records (accrual_date, charge_type);
