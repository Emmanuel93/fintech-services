-- ─────────────────────────────────────────────────────────────────────────────
-- 013: quién puede tomar la línea de distribuidora
--
-- El catálogo declaraba `DISTRIBUTOR` como ÚNICO tipo elegible de DL-DIST-STD-V1. Eso quedó a medio
-- camino de la migración a I-03: `party.PartyType` ya sólo tiene INDIVIDUAL y BUSINESS, y el
-- distribuidor pasó a ser un ROL aditivo en `party.party_roles`. Leído hoy, el producto decía que
-- sólo era elegible un tipo de persona que ya no existe.
--
-- Se corrige a las tres cosas que sí describen a quien puede tomar esta línea:
--
--   INDIVIDUAL   persona física
--   BUSINESS     negocio constituido
--   DISTRIBUTOR  el rol, que es lo que de verdad habilita a colocar crédito a terceros
--
-- Lo que NO va aquí, y conviene dejarlo escrito porque es el error natural: la modalidad de uso
-- —uso propio contra distribución— no es un tipo de party. Vive en las capacidades del producto,
-- en `dispositionType`: el resto del catálogo es SELF_USE y esta línea es THIRD_PARTY_CREDIT, que
-- es de donde wallet saca la exigencia de `beneficiaryPartyId` (DO-03). Meter SELF_USE en esta
-- tabla no sólo lo rechazaría el CHECK: convertiría la línea de distribuidora en otro producto.
--
-- ⚠️  Nada hace cumplir esta tabla todavía: origination no lee las reglas de elegibilidad del
--     catálogo. Esto corrige lo que el producto DICE, no lo que el sistema IMPIDE. La compuerta es
--     trabajo aparte.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO credit_product.credit_product_eligible_party_types (product_definition_id, party_type)
VALUES
    ('a1000001-0000-0000-0000-000000000004', 'INDIVIDUAL'),
    ('a1000001-0000-0000-0000-000000000004', 'BUSINESS')
ON CONFLICT (product_definition_id, party_type) DO NOTHING;
