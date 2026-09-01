--liquibase formatted sql
--changeset accounting:011-bank-suspense author:system
--comment BK-06 · La cuenta puente de la conciliación bancaria, en sus DOS direcciones.

-- **El hueco.** El catálogo sólo tenía `1101 Bancos / SPEI`. Sin cuenta puente, un movimiento que el
-- banco reporta y la plataforma no reconoce no tiene dónde caer: o se fuerza contra una cuenta que
-- no le corresponde, o —lo que pasaba— desaparece del cierre. Una conciliación que no puede
-- declarar lo que no cuadró es un reporte de diferencias, no una conciliación.
--
-- **Por qué son DOS cuentas y no una.** El plan pedía «1109 Depósitos por identificar». Una sola
-- cuenta obliga a que los cargos no aclarados vivan en una cuenta de naturaleza deudora llamada
-- «depósitos», con saldo del signo contrario al de su nombre. Las dos direcciones ocurren y tienen
-- naturaleza opuesta:
--
--   · llegó dinero y no sabemos de quién  → lo DEBEMOS hasta demostrar lo contrario → PASIVO
--   · el banco nos cargó y no sabemos por qué → es un derecho por aclarar          → ACTIVO
--
-- Presentarlas juntas obligaría a compensar activo con pasivo, que es justo lo que un catálogo
-- existe para impedir.
INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('1109', 'Cargos bancarios por aclarar',        'ASSET'),
    ('2109', 'Depósitos por identificar',           'LIABILITY'),
    ('4105', 'Otros ingresos — partidas prescritas','INCOME'),
    ('5105', 'Otros gastos — partidas incobrables', 'EXPENSE');

-- **La reclasificación NO registra el hecho de negocio, sólo saca la partida de la puente.**
-- Cuando un depósito se identifica como el pago de una cuenta, el flujo de pagos postea su
-- `PAYMENT_APPLIED` (1101 → 1201) por su cuenta. Si el asiento de identificación cargara además
-- contra `1201`, el banco subiría dos veces por el mismo depósito. Por eso sale contra `1101`:
-- deshace la pata bancaria de la puente y deja que el flujo real ponga la suya.
INSERT INTO accounting.posting_rules (trigger_event, debit_account, credit_account, description) VALUES
    ('BANK_DEPOSIT_UNIDENTIFIED',   '1101', '2109', 'Abono en banco sin identificar — entra a la puente'),
    ('BANK_DEPOSIT_IDENTIFIED',     '2109', '1101', 'Se identificó el abono — sale de la puente'),
    ('BANK_CHARGE_UNIDENTIFIED',    '1109', '1101', 'Cargo en banco sin aclarar — entra a la puente'),
    ('BANK_CHARGE_CLARIFIED',       '1101', '1109', 'Se aclaró el cargo — sale de la puente'),
    -- `suspense_entries.status` admite WRITTEN_OFF y hasta hoy ese estado no tenía contrapartida
    -- contable: la partida se cerraba en banking y en el mayor seguía viva para siempre.
    ('BANK_SUSPENSE_WRITTEN_OFF',   '2109', '4105', 'Depósito nunca identificado — prescribe'),
    ('BANK_CHARGE_WRITTEN_OFF',     '5105', '1109', 'Cargo nunca aclarado — se asume como gasto');
