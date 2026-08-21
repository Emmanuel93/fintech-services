-- ─────────────────────────────────────────────────────────────────────────────
-- 014: el plazo de una colocación de línea de distribuidora
--
-- Una revolvente no tiene plazo: lo tiene cada disposición. Es la mecánica de una tarjeta con
-- compras a meses — la línea vive indefinidamente y cada compra se amortiza por su cuenta— y es lo
-- que le da a una línea algo que vencer, es decir, la única forma de que caiga en mora.
--
-- El producto tenía min_term, max_term y default_term en NULL, así que no había de dónde sacar a
-- cuántos meses se coloca, y amortization_type en BULLET, que sólo cobra interés y liquida el
-- capital al final: para una colocación a un cliente final lo que corresponde es francesa, cuota
-- fija, que es lo que la persona entiende y lo que el vendedor le promete.
--
-- Los límites son del PRODUCTO, no de la disposición: quien coloca elige dentro de ellos. Sin
-- min/max, «a cuántos meses» sería una pregunta sin respuesta válida ni inválida.
-- ─────────────────────────────────────────────────────────────────────────────

UPDATE credit_product.credit_product_definitions
   SET min_term          = 3,
       max_term          = 24,
       default_term      = 12,
       amortization_type = 'FRENCH'
 WHERE product_definition_id = 'a1000001-0000-0000-0000-000000000004';

-- Lo mismo para la revolvente empresarial, por la misma razón: sus disposiciones también amortizan.
UPDATE credit_product.credit_product_definitions
   SET min_term          = 3,
       max_term          = 36,
       default_term      = 12,
       amortization_type = 'FRENCH'
 WHERE product_definition_id = 'b2000001-0000-0000-0000-000000000002';
