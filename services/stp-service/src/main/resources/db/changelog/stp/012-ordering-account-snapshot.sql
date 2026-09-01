--liquibase formatted sql
--changeset stp-service:012-ordering-account-snapshot
--comment BK-07 · la orden guarda LA cuenta con la que se firmó, no un id que apunta al catálogo de hoy.

-- **Dos cosas se arreglan con las mismas cinco columnas.**
--
-- 1 · La cuenta de la que sale el dinero la decide ahora `banking`, y viaja en el mensaje. Su id no
--     existe en `stp.ordering_accounts`, así que releerla de la tabla local ya no es posible.
--
-- 2 · Y no debía serlo desde antes. `OutboxRelayService` releía la cuenta por id **en el momento de
--     firmar**, que puede ser minutos después de registrar la orden. Cambiar la cuenta ordenante de
--     una empresa en esa ventana cambiaba la cadena original de una orden ya encolada: se firmaba
--     con una cuenta distinta de la que se registró. Guardar la fotografía cierra esa ventana.
ALTER TABLE stp.payment_orders
    ADD COLUMN ordering_clabe          VARCHAR(18),
    ADD COLUMN ordering_holder_name    VARCHAR(150),
    ADD COLUMN ordering_tax_id         VARCHAR(18),
    ADD COLUMN ordering_account_type   VARCHAR(4)  NOT NULL DEFAULT '40',
    ADD COLUMN ordering_client_number  VARCHAR(20);

-- Nullable a propósito: las órdenes que ya existen no tienen fotografía y su relay sigue cayendo al
-- catálogo local. La segunda mitad de la migración (BK-07b) las agota y pone la columna NOT NULL.
COMMENT ON COLUMN stp.payment_orders.ordering_clabe IS
    'La cuenta con la que se firma esta orden. NULL = orden anterior a BK-07, se resuelve del catálogo local.';
