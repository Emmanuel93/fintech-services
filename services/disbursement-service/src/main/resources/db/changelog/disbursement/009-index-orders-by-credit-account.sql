--liquibase formatted sql
--changeset disbursement-service:009-index-orders-by-credit-account
--comment La consulta de tesorería por crédito: «¿qué pagos salieron de esta cuenta?».

-- El crédito no es columna del núcleo —el servicio no habla de crédito—; viaja en el eco opaco de
-- `source_metadata` que cartera manda y disbursement devuelve sin entender. Tesorería pregunta por
-- él desde el backoffice, y sin índice cada consulta barría la tabla entera de pagos.
--
-- Índice por expresión y no columna nueva: se mantiene opaco para el núcleo y no exige reescribir
-- las órdenes que ya existen.
CREATE INDEX disbursement_orders_credit_account_idx
    ON disbursement.disbursement_orders ((source_metadata ->> 'creditAccountId'), created_at DESC);
