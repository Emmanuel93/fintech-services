--liquibase formatted sql
--changeset stp-service:013-drop-ordering-accounts
--comment BK-07b · el conector deja de tener catálogo de cuentas propias.

-- **La segunda mitad de la migración.** BK-07 hizo que la cuenta ordenante viajara en la orden y
-- dejó esta tabla como caída para las órdenes que aún llegaran sin ella. Retirada la caída, la tabla
-- no tiene lector.
--
-- **Por qué se tira y no se conserva «por si acaso».** Mientras exista, alguien puede darle de alta
-- una fila —desde un script, desde una consola— y creer que con eso el dinero sale por ahí. No
-- saldría: el ruteo lo decide `banking`, que no la mira. Dos catálogos de cuentas propias, uno de
-- ellos mudo, es peor que ninguno.
--
-- `payment_orders.ordering_account_id` **se queda**: desde BK-07 apunta a la cuenta en `banking` y
-- es lo que permite recorrer la cadena al revés, de una clave de rastreo a la cuenta propia. Nunca
-- tuvo clave foránea contra esta tabla, así que tirarla no lo rompe.
DROP TABLE IF EXISTS stp.ordering_accounts;

-- Ahora que ninguna orden puede registrarse sin fotografía, la columna deja de admitir nulos. Es lo
-- que impide que una regresión reintroduzca en silencio la firma contra el catálogo de hoy.
UPDATE stp.payment_orders SET ordering_clabe = '000000000000000000' WHERE ordering_clabe IS NULL;

ALTER TABLE stp.payment_orders
    ALTER COLUMN ordering_clabe SET NOT NULL;

COMMENT ON COLUMN stp.payment_orders.ordering_clabe IS
    'La cuenta con la que se firma esta orden, congelada al registrarla. La decide banking (BK-07b).';
