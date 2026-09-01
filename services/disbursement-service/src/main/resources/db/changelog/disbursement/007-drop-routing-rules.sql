--liquibase formatted sql
--changeset disbursement-service:007-drop-routing-rules
--comment BK-08 · el ruteo se muda a `banking.payout_routes`. Aquí queda su partida de defunción.

-- **Por qué se va entera y no se deja "por si acaso".** La tabla decidía el PROVEEDOR y nunca la
-- cuenta; la cuenta la resolvía el conector de STP con un `is_default` por empresa. Media decisión
-- aquí y media allá, y la responsabilidad entera de ninguno de los dos. `banking.payout_routes`
-- toma las dos: mismas columnas, mismo desempate, más `bank_account_id`.
--
-- Dejarla como copia de respaldo sería peor que borrarla: dos catálogos de por dónde sale el dinero,
-- uno de ellos sin lector, esperando a que alguien lo edite creyendo que sirve.
--
-- **No hubo migración de datos porque no había datos.** Ningún script ni changeset sembró nunca una
-- fila: `grep "INSERT INTO disbursement.routing_rules"` en todo el repo no devuelve nada. Que el
-- carril del dinero nunca se ejecutara de verdad —el `Noop` de cartera lo cortocircuitaba antes— es
-- lo que dejó pasar que su configuración jamás se sembrara.
DROP TABLE IF EXISTS disbursement.routing_rules;
