--liquibase formatted sql
--changeset credit-product:018-opciones-de-pago-por-producto
--comment BK-23 · qué puede hacer cada producto: diferir, BNPL, saltar pagos, entrar a un apoyo.

-- **Los productos no declaraban nada de esto, y sin declararlo no lo tienen.** El default de
-- `OpcionesDePago` está todo apagado a propósito: un default permisivo convertiría cada producto
-- del catálogo en uno que admite diferir y saltar pagos sin que nadie lo haya decidido.
--
-- El bloque va anidado en `capabilities`, que ya es JSONB — no hace falta migrar columnas.

-- ── Tarjeta de crédito ───────────────────────────────────────────────────────
-- POST_HOC: la compra nace revolvente pura y el titular la difiere DESPUÉS, antes del corte. Es la
-- mecánica de una tarjeta, opuesta a la del distribuidor.
-- Las bandas son las de un producto real: 3 y 6 meses sin intereses, 9 al 18 %, 12 al 24 %.
UPDATE credit_product.credit_product_definitions
   SET capabilities = capabilities || '{
        "opcionesDePago": {
            "installmentPlanMode": "POST_HOC",
            "deferralCutoffRule": "BEFORE_CUTOFF",
            "deferralDefaultTerm": 3,
            "deferralMinTerm": 3,
            "deferralMaxTerm": 12,
            "bnplEnabled": false,
            "bnplMaxDeferralDays": null,
            "skipPaymentEnabled": true,
            "maxSkipsPerCycle": 1,
            "skipMode": "DEFERRAL",
            "reliefEligible": true,
            "deferralRates": [
                {"minTerm": 3,  "maxTerm": 6,  "nominalRate": 0.0000},
                {"minTerm": 7,  "maxTerm": 9,  "nominalRate": 0.1800},
                {"minTerm": 10, "maxTerm": 12, "nominalRate": 0.2400}
            ]
        }}'::jsonb
 WHERE product_code = 'CC-IND-STD-V1';

-- ── Línea revolvente de uso propio ───────────────────────────────────────────
-- Misma mecánica que la tarjeta pero sin promociones: quien difiere paga la tasa del producto.
UPDATE credit_product.credit_product_definitions
   SET capabilities = capabilities || '{
        "opcionesDePago": {
            "installmentPlanMode": "POST_HOC",
            "deferralCutoffRule": "BEFORE_CUTOFF",
            "deferralDefaultTerm": 3,
            "deferralMinTerm": 3,
            "deferralMaxTerm": 12,
            "bnplEnabled": false,
            "bnplMaxDeferralDays": null,
            "skipPaymentEnabled": false,
            "maxSkipsPerCycle": 0,
            "skipMode": "DEFERRAL",
            "reliefEligible": true,
            "deferralRates": [
                {"minTerm": 3, "maxTerm": 12, "nominalRate": 0.3600}
            ]
        }}'::jsonb
 WHERE product_code = 'RL-IND-STD-V1';

-- ── Préstamo personal ────────────────────────────────────────────────────────
-- No hay nada que diferir en un crédito que nace amortizado. Sí admite BNPL —correr el arranque del
-- pago es una decisión del ALTA— y saltar un pago como recompensa: GIFT, el período no devenga.
UPDATE credit_product.credit_product_definitions
   SET capabilities = capabilities || '{
        "opcionesDePago": {
            "installmentPlanMode": "NONE",
            "deferralCutoffRule": "NONE",
            "deferralDefaultTerm": 3,
            "deferralMinTerm": 1,
            "deferralMaxTerm": 24,
            "bnplEnabled": true,
            "bnplMaxDeferralDays": 30,
            "skipPaymentEnabled": true,
            "maxSkipsPerCycle": 1,
            "skipMode": "GIFT",
            "reliefEligible": true,
            "deferralRates": []
        }}'::jsonb
 WHERE product_code = 'PL-IND-STD-V1';

-- ── Línea de distribuidor ────────────────────────────────────────────────────
-- AT_DISPOSITION: el vendedor decide «a cuántos meses se lo dejas» AL colocar. No se difiere
-- después — la beneficiaria ya firmó su plan.
UPDATE credit_product.credit_product_definitions
   SET capabilities = capabilities || '{
        "opcionesDePago": {
            "installmentPlanMode": "AT_DISPOSITION",
            "deferralCutoffRule": "NONE",
            "deferralDefaultTerm": 3,
            "deferralMinTerm": 3,
            "deferralMaxTerm": 24,
            "bnplEnabled": false,
            "bnplMaxDeferralDays": null,
            "skipPaymentEnabled": false,
            "maxSkipsPerCycle": 0,
            "skipMode": "DEFERRAL",
            "reliefEligible": true,
            "deferralRates": []
        }}'::jsonb
 WHERE product_type = 'DISTRIBUTOR_LINE';
