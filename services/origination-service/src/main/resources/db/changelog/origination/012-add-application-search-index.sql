-- Índice para la bandeja del backoffice.
--
-- La bandeja ordena por fecha de alta descendente y suele acotar por estado. El
-- índice compuesto (status, created_at DESC) sirve al filtro y al orden con la
-- misma estructura; sin él, cada apertura de la bandeja es un sort sobre toda la
-- tabla. Los filtros por prospect_id, product_type y status ya tienen índice
-- (migración 006).
CREATE INDEX IF NOT EXISTS idx_credit_app_status_created
    ON origination.credit_applications (status, created_at DESC);
