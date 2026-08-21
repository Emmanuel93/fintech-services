-- D2 Scoring | Seed de las 4 políticas B2C móviles que faltaban: PAYROLL_LOAN, GROUP_LOAN,
-- CREDIT_CARD, MICRO_LOAN. 007/008 solo cubrieron PERSONAL_LOAN y REVOLVING_LINE — sin esto,
-- la precalificación (POST /api/v1/scoring/prequalify/{prospectId}) nunca puede evaluar los
-- otros 4 tipos de producto B2C disponibles en MOBILE_APP (quedan siempre en NO_ACTIVE_POLICY).
-- Mismas reglas FICO/mora y misma banda de decisión ya usada para INDIVIDUAL/PERSONAL_LOAN y
-- INDIVIDUAL/REVOLVING_LINE (auto-aprueba con score >= 200).

CREATE OR REPLACE FUNCTION scoring.seed_standard_rules(p_policy_id UUID) RETURNS void AS $fn$
BEGIN
    INSERT INTO scoring.scoring_rules
        (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
         score_contribution, is_disqualifying, period_months, description)
    VALUES
        (gen_random_uuid(), p_policy_id, 'MORA_CHECK', NULL, 'GT', 90,
         -500, TRUE, NULL, 'Mora > 90 días → rechazo inmediato'),
        (gen_random_uuid(), p_policy_id, 'MORA_CHECK', 'FM', 'GT', 0,
         -200, FALSE, NULL, 'Mora en créditos Financiera (FM) resta 200'),
        (gen_random_uuid(), p_policy_id, 'FICO_THRESHOLD', NULL, 'GTE', 750,
         200, FALSE, NULL, 'FICO >= 750 suma 200'),
        (gen_random_uuid(), p_policy_id, 'FICO_THRESHOLD', NULL, 'GTE', 700,
         100, FALSE, NULL, 'FICO >= 700 suma 100'),
        (gen_random_uuid(), p_policy_id, 'FICO_THRESHOLD', NULL, 'GTE', 650,
         50, FALSE, NULL, 'FICO >= 650 suma 50'),
        (gen_random_uuid(), p_policy_id, 'CREDIT_COUNT', 'TC', 'LTE', 3,
         50, FALSE, NULL, 'Máx 3 tarjetas suma 50'),
        (gen_random_uuid(), p_policy_id, 'INQUIRY_COUNT', NULL, 'LTE', 5,
         30, FALSE, 12, 'Máx 5 consultas en 12 meses suma 30'),

        -- ── Severidad de la mora, no sólo su duración ────────────────────────
        -- MORA_CHECK mide *cuántos días* se atrasó; estas dos miden *cuánto dinero*. Sin ellas,
        -- quien se atrasó noventa días con dos mil pesos y quien se atrasó noventa días con
        -- doscientos mil obtenían el mismo score, que es la comparación que ningún comité
        -- aceptaría hacer a mano.
        (gen_random_uuid(), p_policy_id, 'WORST_ARREARS_BALANCE', NULL, 'GT', 50000,
         -300, FALSE, NULL, 'Llegó a deber más de $50,000 vencidos: resta 300'),
        (gen_random_uuid(), p_policy_id, 'WORST_ARREARS_BALANCE', NULL, 'LTE', 5000,
         80, FALSE, NULL, 'Su peor mora nunca pasó de $5,000: suma 80'),
        (gen_random_uuid(), p_policy_id, 'BALANCE_CHECK', NULL, 'GT', 20000,
         -250, FALSE, NULL, 'Debe más de $20,000 vencidos hoy: resta 250'),
        (gen_random_uuid(), p_policy_id, 'BALANCE_CHECK', NULL, 'LTE', 0,
         120, FALSE, NULL, 'Sin saldo vencido hoy: suma 120'),

        -- El tiempo cura: un tropiezo de hace tres años no dice lo mismo que uno del mes pasado,
        -- y sin esta regla la política castiga el mismo hecho para siempre.
        (gen_random_uuid(), p_policy_id, 'ARREARS_RECENCY_MONTHS', NULL, 'GTE', 36,
         60, FALSE, NULL, 'Su peor atraso fue hace 3 años o más: suma 60'),
        (gen_random_uuid(), p_policy_id, 'ARREARS_RECENCY_MONTHS', NULL, 'LT', 6,
         -150, FALSE, NULL, 'Se atrasó en los últimos 6 meses: resta 150'),

        -- Marcas del otorgante (quita, fraude, cuenta cedida): descalifican.
        (gen_random_uuid(), p_policy_id, 'PREVENTION_KEY_COUNT', NULL, 'GTE', 1,
         -500, TRUE, NULL, 'Tiene clave de prevención del buró → rechazo inmediato');
END;
$fn$ LANGUAGE plpgsql;

DO $$
DECLARE
    v_payroll UUID := 'b1b2c3d4-0000-0000-0000-000000000004'; -- INDIVIDUAL / PAYROLL_LOAN
    v_group   UUID := 'b1b2c3d4-0000-0000-0000-000000000005'; -- INDIVIDUAL / GROUP_LOAN
    v_card    UUID := 'b1b2c3d4-0000-0000-0000-000000000006'; -- INDIVIDUAL / CREDIT_CARD
    v_micro   UUID := 'b1b2c3d4-0000-0000-0000-000000000007'; -- INDIVIDUAL / MICRO_LOAN
BEGIN
    IF NOT EXISTS (SELECT 1 FROM scoring.scoring_policies WHERE policy_id = v_payroll) THEN
        INSERT INTO scoring.scoring_policies
            (policy_id, prospect_type, product_type_intent, name, description, active, version, created_at)
        VALUES
            (v_payroll, 'INDIVIDUAL', 'PAYROLL_LOAN', 'Crédito de Nómina – Individual (Seed)',
             'Persona física, crédito de nómina. Auto-aprobación por banda de riesgo.', TRUE, 1, NOW());
        PERFORM scoring.seed_standard_rules(v_payroll);
        INSERT INTO scoring.risk_thresholds (threshold_id, policy_id, risk_level, min_score, decision)
        VALUES
            (gen_random_uuid(), v_payroll, 'BAJO',  200,   'AUTO_APPROVED'),
            (gen_random_uuid(), v_payroll, 'MEDIO', 100,   'MANUAL_REVIEW'),
            (gen_random_uuid(), v_payroll, 'ALTO',  -9999, 'REJECTED');
    END IF;

    IF NOT EXISTS (SELECT 1 FROM scoring.scoring_policies WHERE policy_id = v_group) THEN
        INSERT INTO scoring.scoring_policies
            (policy_id, prospect_type, product_type_intent, name, description, active, version, created_at)
        VALUES
            (v_group, 'INDIVIDUAL', 'GROUP_LOAN', 'Crédito Grupal – Individual (Seed)',
             'Persona física, crédito grupal. Auto-aprobación por banda de riesgo.', TRUE, 1, NOW());
        PERFORM scoring.seed_standard_rules(v_group);
        INSERT INTO scoring.risk_thresholds (threshold_id, policy_id, risk_level, min_score, decision)
        VALUES
            (gen_random_uuid(), v_group, 'BAJO',  200,   'AUTO_APPROVED'),
            (gen_random_uuid(), v_group, 'MEDIO', 100,   'MANUAL_REVIEW'),
            (gen_random_uuid(), v_group, 'ALTO',  -9999, 'REJECTED');
    END IF;

    IF NOT EXISTS (SELECT 1 FROM scoring.scoring_policies WHERE policy_id = v_card) THEN
        INSERT INTO scoring.scoring_policies
            (policy_id, prospect_type, product_type_intent, name, description, active, version, created_at)
        VALUES
            (v_card, 'INDIVIDUAL', 'CREDIT_CARD', 'Tarjeta de Crédito – Individual (Seed)',
             'Persona física, tarjeta de crédito. Auto-aprobación por banda de riesgo.', TRUE, 1, NOW());
        PERFORM scoring.seed_standard_rules(v_card);
        INSERT INTO scoring.risk_thresholds (threshold_id, policy_id, risk_level, min_score, decision)
        VALUES
            (gen_random_uuid(), v_card, 'BAJO',  200,   'AUTO_APPROVED'),
            (gen_random_uuid(), v_card, 'MEDIO', 100,   'MANUAL_REVIEW'),
            (gen_random_uuid(), v_card, 'ALTO',  -9999, 'REJECTED');
    END IF;

    IF NOT EXISTS (SELECT 1 FROM scoring.scoring_policies WHERE policy_id = v_micro) THEN
        INSERT INTO scoring.scoring_policies
            (policy_id, prospect_type, product_type_intent, name, description, active, version, created_at)
        VALUES
            (v_micro, 'INDIVIDUAL', 'MICRO_LOAN', 'Micro Préstamo – Individual (Seed)',
             'Persona física, micro préstamo. Auto-aprobación por banda de riesgo.', TRUE, 1, NOW());
        PERFORM scoring.seed_standard_rules(v_micro);
        INSERT INTO scoring.risk_thresholds (threshold_id, policy_id, risk_level, min_score, decision)
        VALUES
            (gen_random_uuid(), v_micro, 'BAJO',  200,   'AUTO_APPROVED'),
            (gen_random_uuid(), v_micro, 'MEDIO', 100,   'MANUAL_REVIEW'),
            (gen_random_uuid(), v_micro, 'ALTO',  -9999, 'REJECTED');
    END IF;
END $$;

-- Las variables de severidad de mora, para **todas** las políticas.
--
-- Las cuatro que siembra este changeset las reciben por la función de arriba, pero las otras
-- cuatro —personal, revolvente, PyME y distribuidor, sembradas en 007 y 008 con sus propios
-- INSERT— se quedaban sin ellas. El efecto era el peor posible: el producto más usado, crédito
-- personal, seguía puntuando sin mirar cuánto llegó a deber el cliente, mientras micro préstamo
-- sí. La misma institución evaluando con dos criterios según el producto.
--
-- Se hace por diferencia y no repitiendo el bloque en cada seed: así una política nueva que
-- nazca sin estas reglas también las recibe, y no hay tres copias del mismo umbral que mantener.
DO $$
DECLARE
    v_policy RECORD;
BEGIN
    FOR v_policy IN
        SELECT policy_id FROM scoring.scoring_policies p
         WHERE NOT EXISTS (SELECT 1 FROM scoring.scoring_rules r
                            WHERE r.policy_id = p.policy_id
                              AND r.rule_type = 'WORST_ARREARS_BALANCE')
    LOOP
        INSERT INTO scoring.scoring_rules
            (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
             score_contribution, is_disqualifying, period_months, description)
        VALUES
            (gen_random_uuid(), v_policy.policy_id, 'WORST_ARREARS_BALANCE', NULL, 'GT', 50000,
             -300, FALSE, NULL, 'Llegó a deber más de $50,000 vencidos: resta 300'),
            (gen_random_uuid(), v_policy.policy_id, 'WORST_ARREARS_BALANCE', NULL, 'LTE', 5000,
             80, FALSE, NULL, 'Su peor mora nunca pasó de $5,000: suma 80'),
            (gen_random_uuid(), v_policy.policy_id, 'BALANCE_CHECK', NULL, 'GT', 20000,
             -250, FALSE, NULL, 'Debe más de $20,000 vencidos hoy: resta 250'),
            (gen_random_uuid(), v_policy.policy_id, 'BALANCE_CHECK', NULL, 'LTE', 0,
             120, FALSE, NULL, 'Sin saldo vencido hoy: suma 120'),
            (gen_random_uuid(), v_policy.policy_id, 'ARREARS_RECENCY_MONTHS', NULL, 'GTE', 36,
             60, FALSE, NULL, 'Su peor atraso fue hace 3 años o más: suma 60'),
            (gen_random_uuid(), v_policy.policy_id, 'ARREARS_RECENCY_MONTHS', NULL, 'LT', 6,
             -150, FALSE, NULL, 'Se atrasó en los últimos 6 meses: resta 150'),
            (gen_random_uuid(), v_policy.policy_id, 'PREVENTION_KEY_COUNT', NULL, 'GTE', 1,
             -500, TRUE, NULL, 'Tiene clave de prevención del buró → rechazo inmediato');
    END LOOP;
END $$;

DROP FUNCTION scoring.seed_standard_rules(UUID);
