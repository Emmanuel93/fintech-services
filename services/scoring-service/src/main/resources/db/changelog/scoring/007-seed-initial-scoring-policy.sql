-- D2 Scoring | Seed: política inicial INDIVIDUAL / PERSONAL_LOAN.
-- Ajustar thresholds y reglas en producción vía API REST.

DO $$
DECLARE
    v_policy_id UUID := 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';
BEGIN
    -- Solo insertar si no existe ya
    IF NOT EXISTS (SELECT 1 FROM scoring.scoring_policies WHERE policy_id = v_policy_id) THEN

        INSERT INTO scoring.scoring_policies
            (policy_id, prospect_type, product_type_intent, name, description, active, version, created_at)
        VALUES
            (v_policy_id, 'INDIVIDUAL', 'PERSONAL_LOAN',
             'Crédito Personal – Individual (Seed)',
             'Política inicial para personas físicas solicitando crédito personal. ' ||
             'Configurar reglas y umbrales según modelo de riesgo definitivo.',
             TRUE, 1, NOW());

        -- ── Reglas ────────────────────────────────────────────────────────
        -- Descalificante: mora > 90 días en cualquier crédito
        INSERT INTO scoring.scoring_rules
            (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
             score_contribution, is_disqualifying, period_months, description)
        VALUES
            (gen_random_uuid(), v_policy_id, 'MORA_CHECK', NULL, 'GT', 90,
             -500, TRUE, NULL, 'Mora > 90 días en cualquier crédito → rechazo inmediato');

        -- Penalización: mora > 0 en créditos de financiera (tipo CDC = FM)
        INSERT INTO scoring.scoring_rules
            (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
             score_contribution, is_disqualifying, period_months, description)
        VALUES
            (gen_random_uuid(), v_policy_id, 'MORA_CHECK', 'FM', 'GT', 0,
             -200, FALSE, NULL, 'Mora en créditos Financiera (FM) resta 200 puntos');

        -- FICO >= 750 → +200
        INSERT INTO scoring.scoring_rules
            (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
             score_contribution, is_disqualifying, period_months, description)
        VALUES
            (gen_random_uuid(), v_policy_id, 'FICO_THRESHOLD', NULL, 'GTE', 750,
             200, FALSE, NULL, 'FICO score >= 750 suma 200 puntos');

        -- FICO >= 700 → +100
        INSERT INTO scoring.scoring_rules
            (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
             score_contribution, is_disqualifying, period_months, description)
        VALUES
            (gen_random_uuid(), v_policy_id, 'FICO_THRESHOLD', NULL, 'GTE', 700,
             100, FALSE, NULL, 'FICO score >= 700 suma 100 puntos');

        -- FICO >= 650 → +50
        INSERT INTO scoring.scoring_rules
            (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
             score_contribution, is_disqualifying, period_months, description)
        VALUES
            (gen_random_uuid(), v_policy_id, 'FICO_THRESHOLD', NULL, 'GTE', 650,
             50, FALSE, NULL, 'FICO score >= 650 suma 50 puntos');

        -- Máx 3 tarjetas de crédito (TC) → +50
        INSERT INTO scoring.scoring_rules
            (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
             score_contribution, is_disqualifying, period_months, description)
        VALUES
            (gen_random_uuid(), v_policy_id, 'CREDIT_COUNT', 'TC', 'LTE', 3,
             50, FALSE, NULL, 'Máximo 3 tarjetas de crédito suma 50 puntos');

        -- Máx 5 consultas en últimos 12 meses → +30
        INSERT INTO scoring.scoring_rules
            (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
             score_contribution, is_disqualifying, period_months, description)
        VALUES
            (gen_random_uuid(), v_policy_id, 'INQUIRY_COUNT', NULL, 'LTE', 5,
             30, FALSE, 12, 'Máximo 5 consultas en 12 meses suma 30 puntos');

        -- ── Umbrales de riesgo ─────────────────────────────────────────────
        INSERT INTO scoring.risk_thresholds
            (threshold_id, policy_id, risk_level, min_score, decision)
        VALUES
            (gen_random_uuid(), v_policy_id, 'BAJO',  200,   'AUTO_APPROVED'),
            (gen_random_uuid(), v_policy_id, 'MEDIO', 100,   'MANUAL_REVIEW'),
            (gen_random_uuid(), v_policy_id, 'ALTO',  -9999, 'REJECTED');

    END IF;
END $$;
