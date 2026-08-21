-- ─────────────────────────────────────────────────────────────────────────────
-- 015: el tope de lo que un distribuidor puede colocarle a UN beneficiario
--
-- La 014 resolvió el plazo de la colocación. Falta el monto, y no es el mismo número que la
-- línea: `min_credit_line`/`max_credit_line` acotan lo que el comité le autoriza al distribuidor
-- —cuánto puede deber en total—, mientras que `min_amount`/`max_amount` acotan **cada
-- colocación**: cuánto puede darle a una sola persona.
--
-- Sin ese tope, un distribuidor con línea de $500,000 podría colocarle los $500,000 completos a
-- un solo beneficiario, y toda su línea quedaría colgada del historial de un desconocido. El
-- límite por persona es lo que obliga a diversificar, y por eso es del producto y no del
-- distribuidor: es una regla de riesgo, no una preferencia comercial.
--
-- Se configura por producto igual que la tasa y los plazos, y se cambia sin redeploy.
-- ─────────────────────────────────────────────────────────────────────────────

-- Escalón del plazo. `amount_step` ya existía con esta misma lógica; sin su gemelo, un stepper de
-- plazos «cada 12» (12/24/36/48) o «cada 2» no se puede expresar con sólo min y max.
ALTER TABLE credit_product.credit_product_definitions
    ADD COLUMN term_step INTEGER NOT NULL DEFAULT 1;

ALTER TABLE credit_product.credit_product_definitions
    ADD CONSTRAINT ck_term_step CHECK (term_step >= 1);

-- Que el rango sea coherente: un mínimo por encima del máximo es una configuración que ningún
-- monto satisface, y vale más que reviente al guardarla que al colocar.
ALTER TABLE credit_product.credit_product_definitions
    ADD CONSTRAINT ck_amount_range CHECK (
        min_amount IS NULL OR max_amount IS NULL OR min_amount <= max_amount);

ALTER TABLE credit_product.credit_product_definitions
    ADD CONSTRAINT ck_term_range CHECK (
        min_term IS NULL OR max_term IS NULL OR min_term <= max_term);

-- ── Línea Distribuidora (DL-DIST-STD-V1) ────────────────────────────────────
-- Tope por beneficiario: $60,000, que es donde termina el slider del diseño. El piso, $5,000.
-- El escalón de monto pasa de 5000 a 1000: el 5000 acotaba la LÍNEA, y para una colocación el
-- diseño mueve de mil en mil.
UPDATE credit_product.credit_product_definitions
   SET min_amount  = 5000.00,
       max_amount  = 60000.00,
       amount_step = 1000,
       term_step   = 1
 WHERE product_definition_id = 'a1000001-0000-0000-0000-000000000004';

-- ── Revolvente empresarial (b2000001-…-0002) ────────────────────────────────
-- Misma mecánica: sus disposiciones también amortizan y también conviene acotarlas.
UPDATE credit_product.credit_product_definitions
   SET min_amount  = 10000.00,
       max_amount  = 500000.00,
       amount_step = 1000,
       term_step   = 1
 WHERE product_definition_id = 'b2000001-0000-0000-0000-000000000002';
