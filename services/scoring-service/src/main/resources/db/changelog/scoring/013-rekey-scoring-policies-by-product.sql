-- ─────────────────────────────────────────────────────────────────────────────
-- 013: la política de scoring se busca por PRODUCTO, no por tipo de persona
--
-- El síntoma. Una solicitud de línea de distribuidora no recibía decisión: `findActiveBy` buscaba
-- por (prospect_type, product_type_intent), la política estaba sembrada contra
-- prospect_type='DISTRIBUTOR', y desde que el distribuidor pasó a ser un ROL (I-03) el prospecto
-- llega como INDIVIDUAL o BUSINESS. No encontraba nada, y el motor **omite con un warning** en vez
-- de fallar — así que la solicitud se quedaba quieta para siempre sin que nada lo dijera.
--
-- Por qué se quita el tipo de persona de la llave, y no se «arregla» sembrando más filas. Se revisó
-- lo que hay sembrado (007, 008, 010): **ningún product_type aparece bajo dos prospect_type
-- distintos**. La primera mitad de la llave no discrimina nada; lo único que hace es obligar a que
-- quien pregunta acierte un valor, y ése es exactamente el modo en que esto falla. Una llave que no
-- separa nada y sí puede fallar no es una llave: es una trampa.
--
-- Y es lo correcto, no sólo lo barato: lo que cambia las reglas de riesgo no es qué ES el
-- solicitante, sino qué producto pide y en qué modalidad se usa. La modalidad ya la declara el
-- catálogo en `capabilities.dispositionType` (SELF_USE para uso propio, THIRD_PARTY_CREDIT para la
-- línea de distribuidora, PAYROLL para nómina), junto con los montos, los límites, las tasas por
-- tramo y el flujo de aprobación. La política de scoring pasa a buscarse por la misma llave que
-- todo lo demás del producto.
--
-- `prospect_type` NO se borra. Se queda como descripción de para quién se escribió la política, que
-- es parte de la historia de una decisión de crédito. Deja de gobernar la búsqueda y pasa a ser
-- opcional. Borrarlo reescribiría el porqué de evaluaciones ya tomadas.
-- ─────────────────────────────────────────────────────────────────────────────

-- Antes de tocar la llave, comprobar la premisa. Si mañana alguien sembró dos políticas activas
-- para el mismo producto, colapsar la llave las volvería indistinguibles y el índice único fallaría
-- a media migración. Mejor detenerse aquí, con el motivo escrito, que dejar el esquema a medias.
DO $$
DECLARE
    v_dup TEXT;
BEGIN
    SELECT string_agg(product_type_intent, ', ')
      INTO v_dup
      FROM (SELECT product_type_intent
              FROM scoring.scoring_policies
             WHERE active
             GROUP BY product_type_intent
            HAVING COUNT(*) > 1) d;

    IF v_dup IS NOT NULL THEN
        RAISE EXCEPTION
            'No se puede re-llavear: hay más de una política ACTIVA para el mismo producto (%). '
            'Desactivá las sobrantes antes de aplicar esta migración.', v_dup;
    END IF;
END $$;

DROP INDEX IF EXISTS scoring.idx_scoring_policies_active_unique;

-- Una sola política vigente por producto.
CREATE UNIQUE INDEX idx_scoring_policies_active_unique
    ON scoring.scoring_policies (product_type_intent)
    WHERE active = TRUE;

-- Descriptivo a partir de aquí: se conserva lo ya escrito y se deja de exigir en las altas nuevas.
ALTER TABLE scoring.scoring_policies ALTER COLUMN prospect_type DROP NOT NULL;

COMMENT ON TABLE scoring.scoring_policies IS
    'Políticas de scoring configurables. Una activa por product_type_intent. La modalidad de uso '
    '(uso propio / distribución) la declara el producto en capabilities.dispositionType.';
COMMENT ON COLUMN scoring.scoring_policies.prospect_type IS
    'Descriptivo: para quién se escribió la política. NO forma parte de la llave de búsqueda desde '
    'la migración 013 — se conserva porque es parte de la historia de las decisiones ya tomadas.';


-- ─────────────────────────────────────────────────────────────────────────────
-- La política que faltaba: BUSINESS_REVOLVING_LINE
--
-- Encontrada al revisar esto. El producto BRL-BUS-STD-V1 está ACTIVE en el catálogo desde la
-- siembra 012 de credit-product y **nunca tuvo política de scoring**: tenía exactamente el mismo
-- síntoma que la línea de distribuidora —solicitud sin decisión, omitida con un warning— en un
-- producto que nadie había mirado porque nadie lo había pedido todavía.
--
-- Mismas reglas estándar que el resto y banda de comité, igual que los otros dos revolventes
-- grandes (PYME y distribuidora): una línea empresarial no se auto-aprueba.
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    -- 001–007 ya están tomados por las siembras 008 y 010. Ocho es el siguiente libre.
    v_brl UUID := 'b1b2c3d4-0000-0000-0000-000000000008'; -- BUSINESS_REVOLVING_LINE
BEGIN
    IF EXISTS (SELECT 1 FROM scoring.scoring_policies
                WHERE product_type_intent = 'BUSINESS_REVOLVING_LINE' AND active) THEN
        RETURN;
    END IF;

    INSERT INTO scoring.scoring_policies
        (policy_id, prospect_type, product_type_intent, name, description, active, version, created_at)
    VALUES
        (v_brl, 'BUSINESS', 'BUSINESS_REVOLVING_LINE', 'Línea Revolvente Empresarial (Seed)',
         'Empresa, línea revolvente de capital de trabajo. Todo score no descalificado va a comité.',
         TRUE, 1, NOW());

    INSERT INTO scoring.scoring_rules
        (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
         score_contribution, is_disqualifying, period_months, description)
    VALUES
        (gen_random_uuid(), v_brl, 'MORA_CHECK', NULL, 'GT', 90,
         -500, TRUE, NULL, 'Mora > 90 días → rechazo inmediato'),
        (gen_random_uuid(), v_brl, 'MORA_CHECK', 'FM', 'GT', 0,
         -200, FALSE, NULL, 'Mora en créditos Financiera (FM) resta 200'),
        (gen_random_uuid(), v_brl, 'FICO_THRESHOLD', NULL, 'GTE', 750,
         200, FALSE, NULL, 'FICO >= 750 suma 200'),
        (gen_random_uuid(), v_brl, 'FICO_THRESHOLD', NULL, 'GTE', 700,
         100, FALSE, NULL, 'FICO >= 700 suma 100'),
        (gen_random_uuid(), v_brl, 'FICO_THRESHOLD', NULL, 'GTE', 650,
         50, FALSE, NULL, 'FICO >= 650 suma 50'),
        (gen_random_uuid(), v_brl, 'CREDIT_COUNT', 'TC', 'LTE', 3,
         50, FALSE, NULL, 'Máx 3 tarjetas suma 50'),
        (gen_random_uuid(), v_brl, 'INQUIRY_COUNT', NULL, 'LTE', 5,
         30, FALSE, 12, 'Máx 5 consultas en 12 meses suma 30');

    INSERT INTO scoring.risk_thresholds (threshold_id, policy_id, risk_level, min_score, decision)
    VALUES
        (gen_random_uuid(), v_brl, 'BAJO',  100000, 'AUTO_APPROVED'),  -- inalcanzable: a comité
        (gen_random_uuid(), v_brl, 'MEDIO', -9998,  'MANUAL_REVIEW'),
        (gen_random_uuid(), v_brl, 'ALTO',  -9999,  'REJECTED');
END $$;
