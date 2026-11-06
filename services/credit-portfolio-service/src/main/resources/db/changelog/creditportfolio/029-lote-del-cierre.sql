--liquibase formatted sql
--changeset credit-portfolio:029-lote-del-cierre
--comment Los cargos del cierre del día D llevan D: las cifras de control cuentan sólo ese lote.

-- Por fecha valor no cuadra: la 027 fechó la historia por applied_at, y las siembras o el soporte de
-- pruebas aplican cargos del mismo día que no salieron del cierre. El lote es exacto.
ALTER TABLE credit_portfolio.balance_events ADD COLUMN IF NOT EXISTS day_close_date DATE;
ALTER TABLE credit_portfolio.charge_rejections ADD COLUMN IF NOT EXISTS day_close_date DATE;
CREATE INDEX IF NOT EXISTS idx_balance_events_day_close
    ON credit_portfolio.balance_events (day_close_date, event_type) WHERE day_close_date IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_charge_rejections_day_close
    ON credit_portfolio.charge_rejections (day_close_date, charge_type) WHERE day_close_date IS NOT NULL;
