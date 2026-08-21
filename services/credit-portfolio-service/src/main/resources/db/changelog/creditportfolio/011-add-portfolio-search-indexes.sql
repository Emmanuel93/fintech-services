-- Índices del listado del backoffice.
--
-- Son consultas constantes: la pantalla de cartera se abre a diario, filtra por
-- estado y producto, ordena por fecha de alta y busca por número de contrato.
-- Sin índices, cada carga es un seq scan sobre una tabla que sólo crece.

-- El listado ordena por created_at descendente dentro de un estado. El índice
-- compuesto sirve al filtro y al orden con la misma estructura.
CREATE INDEX IF NOT EXISTS idx_credit_accounts_status_created
    ON credit_portfolio.credit_accounts (status, created_at DESC);

-- Filtro por tipo de producto, casi siempre junto al estado.
CREATE INDEX IF NOT EXISTS idx_credit_accounts_product_type
    ON credit_portfolio.credit_accounts (product_type, status);

-- Cartera vencida: el tablero y el listado de cobranza filtran por días de
-- atraso. Parcial, porque la inmensa mayoría de las cuentas están al corriente
-- y no tiene sentido indexarlas para esta consulta.
CREATE INDEX IF NOT EXISTS idx_credit_accounts_delinquent
    ON credit_portfolio.credit_accounts (days_delinquent DESC, status)
    WHERE days_delinquent > 0;

-- Búsqueda por número de contrato: el usuario pega un pedazo del folio, así que
-- la consulta es `LIKE '%texto%'`. Con el comodín a la izquierda un btree no
-- sirve —verificado con EXPLAIN forzando enable_seqscan=off, seguía en seq
-- scan—; el índice que sí resuelve "contiene" es un GIN de trigramas.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_credit_accounts_contract_number_trgm
    ON credit_portfolio.credit_accounts
    USING gin (LOWER(contract_number) gin_trgm_ops);

-- El calendario se lee siempre por cuenta y en orden de mensualidad: es la
-- consulta del seguimiento quincena a quincena.
CREATE INDEX IF NOT EXISTS idx_installments_schedule_number
    ON credit_portfolio.installments (schedule_id, installment_number);
