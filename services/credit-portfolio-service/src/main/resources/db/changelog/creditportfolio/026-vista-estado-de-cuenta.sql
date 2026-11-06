--liquibase formatted sql
--changeset credit-portfolio:026-vista-estado-de-cuenta
--comment El estado de cuenta lee el libro de la cuenta por período.

-- La vista del estado de cuenta pide, por cada cuenta que corta, sus movimientos entre dos fechas y
-- el último antes del período. Con el índice sólo por cuenta, cada lectura recorría la historia
-- entera: un año de devengo diario son ~700 filas por cuenta, y el corte las pide todas a la vez.
CREATE INDEX IF NOT EXISTS idx_balance_events_account_applied
    ON credit_portfolio.balance_events (credit_account_id, applied_at);
