-- ─────────────────────────────────────────────────────────────────────────────
-- 016: la colocación de una línea de distribuidora se paga QUINCENALMENTE
--
-- La 014 dejó el producto en MONTHLY y razonó los plazos en meses. El diseño y la app hablan de
-- quincenas de punta a punta —«en cuántas quincenas te paga», `termFortnights`,
-- `fortnightlyPayment`—, así que la cadencia y la unidad del plazo estaban diciendo una cosa y el
-- producto otra.
--
-- Se usa BIWEEKLY, que ya existe en el enum y en el motor de amortización; no se agrega una
-- cadencia nueva. Los plazos se reexpresan en la misma unidad: los 3–24 MESES de la 014 son
-- 6–48 QUINCENAS, que además cubre los 12/24/36/48 que ofrece el stepper del diseño.
--
-- CONSECUENCIA A VERIFICAR CON PRODUCTO: `Cadence.BIWEEKLY` son 26 períodos al año (cada 14
-- días), no 24. El $868.06 del contrato de la app sale de dividir la tasa entre 24. Con 26 el
-- pago de $18,000 a 24 períodos es ~$858.97 y los vencimientos corren cada 14 días en vez de
-- caer el 15 y el último. Si lo que se quiere es que el cobro caiga junto a la nómina, hace
-- falta una cadencia semimensual; si 26 está bien, el número de la app se ajusta.
-- ─────────────────────────────────────────────────────────────────────────────

UPDATE credit_product.credit_product_definitions
   SET default_payment_frequency = 'BIWEEKLY',
       min_term                  = 6,
       max_term                  = 48,
       default_term              = 24
 WHERE product_definition_id = 'a1000001-0000-0000-0000-000000000004';

-- La cadencia por defecto tiene que estar entre las permitidas, o el producto ofrecería algo que
-- él mismo no acepta.
INSERT INTO credit_product.credit_product_payment_frequencies (product_definition_id, payment_frequency)
VALUES ('a1000001-0000-0000-0000-000000000004', 'BIWEEKLY')
ON CONFLICT DO NOTHING;
