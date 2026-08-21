--liquibase formatted sql
--changeset invoicing:005-invoice-line-account-index author:system
--comment Buscar las facturas de un préstamo sin conocer al cliente.

-- La única forma de llegar a una factura era conociendo su id o el del party. Quien concilia un
-- contrato tiene el número de crédito a la mano, no el identificador interno del cliente, así que
-- la pregunta «qué se le facturó a este préstamo» no tenía respuesta por API.
--
-- El dato ya estaba en las líneas; le faltaba el índice para que el cruce no fuera un recorrido de
-- toda la tabla de renglones en cada búsqueda.
CREATE INDEX idx_invoice_lines_account
    ON invoicing.invoice_lines (credit_account_id)
    WHERE credit_account_id IS NOT NULL;

CREATE INDEX idx_invoices_period_status ON invoicing.invoices (period, status);
