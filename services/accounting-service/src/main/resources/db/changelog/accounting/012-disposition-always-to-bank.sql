--liquibase formatted sql
--changeset accounting:012-disposition-always-to-bank author:system
--comment BK-11 · toda disposición sale por banco. El monedero queda fuera de alcance.

-- `DISPOSITION_SELF_USE` asentaba `1201 → 2101` (Fondos de clientes por disponer): el dinero se
-- quedaba en la plataforma como saldo a favor. Con el monedero en hold, **toda disposición
-- desemboca en una cuenta bancaria** —la del titular en un producto de uso propio, la de la
-- beneficiaria en una línea de distribuidor— y el abono es a `1101`, que sí tiene contraparte
-- bancaria real y por tanto se puede conciliar.
--
-- El asiento sigue emitiéndose sólo cuando hay evidencia del proveedor: hasta esta rama se emitía
-- al autorizar, contra un stub, y el mayor registraba una salida de caja de dinero que nunca salió.
UPDATE accounting.posting_rules
   SET credit_account = '1101',
       description    = 'Disposición de uso propio — sale a la cuenta del titular'
 WHERE trigger_event = 'DISPOSITION_SELF_USE';

-- La devolución del banco receptor y el fallo del proveedor deshacen la disposición: sin regla, la
-- reversa de cartera no tenía contrapartida en el mayor y el activo se quedaba inflado.
INSERT INTO accounting.posting_rules (trigger_event, debit_account, credit_account, description) VALUES
    ('DISPOSITION_RETURNED', '1101', '1201', 'El banco receptor devolvió la disposición'),
    ('DISPOSITION_FAILED',   '1101', '1201', 'El pago no salió — se deshace la disposición');
