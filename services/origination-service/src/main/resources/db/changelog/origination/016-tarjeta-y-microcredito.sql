--liquibase formatted sql
--changeset origination-service:016-tarjeta-y-microcredito
--comment La TERCERA barrera que impedía originar una tarjeta.

-- **Tres barreras independientes, y cada una escondía a la siguiente.**
--
--   1. `seed-portfolio.Catalogo` descarta todo producto cuyo `behavior` no sea INSTALLMENT,
--      así que el sembrador nunca elegía una tarjeta.
--   2. `origination.ProductType` no incluía CREDIT_CARD, así que la solicitud moría en la
--      deserialización con un 400.
--   3. Y este CHECK tampoco lo lista, así que la fila rebota en la base con un 409.
--
-- Sólo se ve la tercera al quitar las dos primeras. Es la razón de fondo por la que la demo no tenía
-- ni una tarjeta viva, y por la que ningún escenario del grupo B se podía probar contra datos
-- reales.
--
-- El catálogo tiene los dos productos ACTIVOS desde su seed y `scoring` tiene sus políticas de
-- riesgo listas y activas. Todo lo demás los soportaba: faltaban aquí y en el enum.
ALTER TABLE origination.credit_applications DROP CONSTRAINT ck_credit_app_product;

ALTER TABLE origination.credit_applications
    ADD CONSTRAINT ck_credit_app_product CHECK (product_type IN (
        'PERSONAL_LOAN',
        'REVOLVING_LINE',
        'CREDIT_CARD',
        'MICRO_LOAN',
        'PAYROLL_LOAN',
        'GROUP_LOAN',
        'DISTRIBUTOR_LINE',
        'SME_LOAN',
        'BUSINESS_REVOLVING_LINE'
    ));
