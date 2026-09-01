-- liquibase formatted sql

-- changeset credit-product:019-opciones-de-pago-coherentes
-- Quitar de cada producto los parámetros de algo que ese producto no hace.
--
-- Un producto no se configura campo a campo: los campos **se condicionan entre sí**, y una
-- combinación puede ser inválida sin que ninguno de sus valores lo sea. Eso no lo caza nadie
-- revisando el JSON a ojo, y no rompe nada — sólo miente sobre lo que el producto hace. El síntoma
-- aparece lejos: «el producto PL-IND-STD-V1 no admite saltar pagos» cuando el catálogo dice que sí.
--
-- El préstamo personal declaraba `installmentPlanMode: NONE` —no difiere nada— y a la vez plazos de
-- diferimiento de 1 a 24 con 3 por omisión. Parámetros para una capacidad que no tiene. Quien
-- leyera esa configuración para decidir si ofrecer «pagar a meses» encontraría un rango completo y
-- se lo creería.
--
-- `deferralRates: []` sí se queda en los que no difieren después: una lista vacía dice
-- explícitamente «ninguna banda», que es distinto de que el campo no exista.
UPDATE credit_product.credit_product_definitions
   SET capabilities = jsonb_set(
           capabilities,
           '{opcionesDePago}',
           (capabilities->'opcionesDePago')
               - 'deferralDefaultTerm' - 'deferralMinTerm' - 'deferralMaxTerm')
 WHERE capabilities->'opcionesDePago'->>'installmentPlanMode' = 'NONE'
   -- `jsonb_exists_any` y no `?|`: los operadores JSONB con interrogación chocan con los
   -- marcadores de parámetro de JDBC, así que por Liquibase esto es un error de sintaxis aunque
   -- por psql funcione. La función es el mismo operador sin el signo.
   AND jsonb_exists_any(capabilities->'opcionesDePago',
                        array['deferralDefaultTerm','deferralMinTerm','deferralMaxTerm']);

-- Y el modo de salto en productos que no lo ofrecen: declarar `skipMode` sobre un salto apagado es
-- la misma clase de ruido. Si mañana se enciende, quien lo encienda decide el modo a propósito en
-- vez de heredar uno que nadie eligió.
UPDATE credit_product.credit_product_definitions
   SET capabilities = jsonb_set(
           capabilities,
           '{opcionesDePago}',
           (capabilities->'opcionesDePago') - 'skipMode')
 WHERE capabilities->'opcionesDePago'->>'skipPaymentEnabled' = 'false'
   AND jsonb_exists(capabilities->'opcionesDePago', 'skipMode');
