--liquibase formatted sql
--changeset accounting:021-cierre-del-ejercicio
--comment The year-end close: the year's result goes to retained earnings (3201) in period 13.

-- Revenue and expense accounts measure one year. Until now nothing reset them: the ledger is read cumulatively, so
-- January's trial balance would carry every revenue and expense since the first day. The December close now books
-- the closing voucher (revenue and expense to zero against 3201) in period YYYY13, as SAT's electronic accounting
-- sends it (Anexo 24, "balanza de cierre del ejercicio", month 13): December keeps its income statement; January
-- starts from zero and the equity shows the result of earlier years.
INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('3201', 'Resultado de ejercicios anteriores', 'EQUITY')
ON CONFLICT (code) DO NOTHING;
