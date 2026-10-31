--liquibase formatted sql
--changeset scoring:017-politica-sin-historial
--comment Quien no tiene historial en buró se decide con la política de su producto, no con una regla global.

-- Hasta aquí, NO_HIT y NOT_FOUND salían por una sola variable de ambiente para todos los productos.
-- Lo que se le hace a quien no tiene historial es una decisión de cada producto: el préstamo personal
-- lo rechaza, la línea de distribuidora lo revisa, y uno para estudiantes —cuyo público casi nunca
-- tiene historial— puede revisarlo pidiendo una constancia de estudios.
--
-- El score sin historial pasa por las bandas de la política como cualquier otro. Las políticas que ya
-- existen quedan en 0, que es lo que se decidió: con sus bandas actuales, los productos de persona
-- física caen en ALTO (rechazo) y los de distribuidora y empresa en MEDIO (revisión).
ALTER TABLE scoring.scoring_policies
    ADD COLUMN IF NOT EXISTS no_history_score INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS no_history_requirements TEXT[] NOT NULL DEFAULT '{}';

-- La lista es cerrada: lo que se pide lo revisa una persona y lo nombra la app.
ALTER TABLE scoring.scoring_policies
    ADD CONSTRAINT chk_scoring_policies_no_history_requirements
    CHECK (no_history_requirements <@ ARRAY[
        'PROOF_OF_ENROLLMENT', 'INCOME_PROOF', 'ADDRESS_PROOF',
        'BANK_STATEMENT_3M', 'GUARANTOR', 'PERSONAL_REFERENCES']::TEXT[]);

COMMENT ON COLUMN scoring.scoring_policies.no_history_score IS
    'Score asignado cuando el buró no tiene historial (NO_HIT/NOT_FOUND); pasa por las bandas de la política';
COMMENT ON COLUMN scoring.scoring_policies.no_history_requirements IS
    'Datos adicionales que se piden a quien no tiene historial cuando la decisión es revisión manual';
