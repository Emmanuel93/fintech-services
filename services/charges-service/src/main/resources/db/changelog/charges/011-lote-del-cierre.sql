--liquibase formatted sql
--changeset charges:011-lote-del-cierre
--comment Cada cargo que genera el cierre del día D lleva D: las cifras de control cuentan sólo ese lote.

-- Comparar por fecha valor no basta: hay cargos del mismo día que no salieron del cierre (siembras,
-- soporte de pruebas, historia anterior a la fecha valor). El lote es exacto: lo que este cierre generó.
ALTER TABLE charges.charge_records ADD COLUMN IF NOT EXISTS day_close_date DATE;
CREATE INDEX IF NOT EXISTS idx_charge_records_day_close
    ON charges.charge_records (day_close_date, charge_type) WHERE day_close_date IS NOT NULL;
