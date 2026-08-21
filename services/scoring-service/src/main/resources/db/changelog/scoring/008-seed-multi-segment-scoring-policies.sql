-- D2 Scoring | Seed multi-segmento: habilita el journey para B2C (revolvente), B2B y B2B2C.
-- Complementa 007 (INDIVIDUAL/PERSONAL_LOAN). Mismas reglas FICO/mora; lo que cambia por
-- segmento es la banda de decisión:
--   * B2C (INDIVIDUAL/REVOLVING_LINE): auto-aprueba con score >= 200 (igual que PERSONAL_LOAN).
--   * B2B (BUSINESS/SME_LOAN) y B2B2C (DISTRIBUTOR/DISTRIBUTOR_LINE): la banda AUTO_APPROVED es
--     inalcanzable a propósito → todo score no descalificado cae en MANUAL_REVIEW, que origination
--     enruta a comité/underwriting (decisión de ops). Así se ejerce el flujo de comité end-to-end.

-- Reglas FICO/mora/conteo estándar reutilizadas por cada política.
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
         30, FALSE, 12, 'Máx 5 consultas en 12 meses suma 30');
END;
$fn$ LANGUAGE plpgsql;

DO $$
DECLARE
    v_b2c UUID := 'b1b2c3d4-0000-0000-0000-000000000001'; -- INDIVIDUAL / REVOLVING_LINE
    v_b2b UUID := 'b1b2c3d4-0000-0000-0000-000000000002'; -- BUSINESS   / SME_LOAN
    v_dst UUID := 'b1b2c3d4-0000-0000-0000-000000000003'; -- DISTRIBUTOR/ DISTRIBUTOR_LINE
BEGIN
    -- ── B2C: Línea Revolvente Personal (auto-aprueba) ──────────────────────
    IF NOT EXISTS (SELECT 1 FROM scoring.scoring_policies WHERE policy_id = v_b2c) THEN
        INSERT INTO scoring.scoring_policies
            (policy_id, prospect_type, product_type_intent, name, description, active, version, created_at)
        VALUES
            (v_b2c, 'INDIVIDUAL', 'REVOLVING_LINE', 'Línea Revolvente – Individual (Seed)',
             'Persona física, línea revolvente. Auto-aprobación por banda de riesgo.', TRUE, 1, NOW());
        PERFORM scoring.seed_standard_rules(v_b2c);
        INSERT INTO scoring.risk_thresholds (threshold_id, policy_id, risk_level, min_score, decision)
        VALUES
            (gen_random_uuid(), v_b2c, 'BAJO',  200,   'AUTO_APPROVED'),
            (gen_random_uuid(), v_b2c, 'MEDIO', 100,   'MANUAL_REVIEW'),
            (gen_random_uuid(), v_b2c, 'ALTO',  -9999, 'REJECTED');
    END IF;

    -- ── B2B: Crédito PYME (a comité) ───────────────────────────────────────
    IF NOT EXISTS (SELECT 1 FROM scoring.scoring_policies WHERE policy_id = v_b2b) THEN
        INSERT INTO scoring.scoring_policies
            (policy_id, prospect_type, product_type_intent, name, description, active, version, created_at)
        VALUES
            (v_b2b, 'BUSINESS', 'SME_LOAN', 'Crédito PYME – Empresa (Seed)',
             'Empresa (B2B). Todo score no descalificado va a revisión de comité/underwriting.',
             TRUE, 1, NOW());
        PERFORM scoring.seed_standard_rules(v_b2b);
        INSERT INTO scoring.risk_thresholds (threshold_id, policy_id, risk_level, min_score, decision)
        VALUES
            (gen_random_uuid(), v_b2b, 'BAJO',  100000, 'AUTO_APPROVED'),  -- inalcanzable
            (gen_random_uuid(), v_b2b, 'MEDIO', -9998,  'MANUAL_REVIEW'),
            (gen_random_uuid(), v_b2b, 'ALTO',  -9999,  'REJECTED');
    END IF;

    -- ── B2B2C: Línea Distribuidor (a comité) ───────────────────────────────
    IF NOT EXISTS (SELECT 1 FROM scoring.scoring_policies WHERE policy_id = v_dst) THEN
        INSERT INTO scoring.scoring_policies
            (policy_id, prospect_type, product_type_intent, name, description, active, version, created_at)
        VALUES
            (v_dst, 'DISTRIBUTOR', 'DISTRIBUTOR_LINE', 'Línea Distribuidor – (Seed)',
             'Distribuidor (B2B2C). El obligor es el distribuidor; disposición a terceros. A comité.',
             TRUE, 1, NOW());
        PERFORM scoring.seed_standard_rules(v_dst);
        INSERT INTO scoring.risk_thresholds (threshold_id, policy_id, risk_level, min_score, decision)
        VALUES
            (gen_random_uuid(), v_dst, 'BAJO',  100000, 'AUTO_APPROVED'),  -- inalcanzable
            (gen_random_uuid(), v_dst, 'MEDIO', -9998,  'MANUAL_REVIEW'),
            (gen_random_uuid(), v_dst, 'ALTO',  -9999,  'REJECTED');
    END IF;
END $$;

DROP FUNCTION scoring.seed_standard_rules(UUID);
