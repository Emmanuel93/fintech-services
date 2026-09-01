--liquibase formatted sql
--changeset charges:007-mora-sobre-capital-vencido
--comment BK-19 · el moratorio se devenga sobre el CAPITAL VENCIDO, no sobre el saldo completo.

-- **El defecto.** `accrueMoratoriumForSchedule` usaba `principal_balance` —todo el saldo del
-- crédito— como base del moratorio. Sobre un crédito de $20 000 con una cuota vencida cuyo capital
-- es $1 800, cobraba mora sobre $20 000: **once veces lo que corresponde**.
--
-- Y no se notaba porque el moratorio nunca llegó a devengarse: `activateMoratorium` no tenía
-- llamador de producción. Conectar el listener (BK-18) sin corregir esto habría encendido el cobro
-- multiplicado en la primera corrida — por eso esta columna va PRIMERO.
ALTER TABLE charges.accrual_schedules
    ADD COLUMN overdue_principal NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN oldest_due_date   DATE;

COMMENT ON COLUMN charges.accrual_schedules.overdue_principal IS
    'Capital de las cuotas vencidas sin cubrir. Base del moratorio. Lo publica cartera en delinquency-status-updated.';
COMMENT ON COLUMN charges.accrual_schedules.oldest_due_date IS
    'Vencimiento de la cuota vencida más antigua. Es la fecha desde la que corre la mora, pasada la gracia.';

-- BK-21 · la gracia tenía dos fuentes con valores distintos: `grace_period_days` (3, el vigente en
-- charges) y `close_cycle_policies.payment_due_offset_days` (0, el del motor de cierres). Gana el
-- vigente, y se declara aquí para que no vuelva a divergir en silencio.
ALTER TABLE charges.accrual_schedules
    ALTER COLUMN grace_period_days SET DEFAULT 3;
