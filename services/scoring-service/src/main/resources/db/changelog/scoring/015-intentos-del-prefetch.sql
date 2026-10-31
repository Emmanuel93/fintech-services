--liquibase formatted sql
--changeset scoring:015-intentos-del-prefetch
--comment Cuántas veces se llamó al buró en cada prefetch, con reintentos.

-- Un prefetch FAILED decía por qué, no cuántas veces se intentó. Con reintento por backoff, «falló
-- al primer intento» y «falló tras cuatro» son incidentes distintos: uno es un dato malo, el otro
-- un buró caído.
ALTER TABLE scoring.bureau_prefetches
    ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN scoring.bureau_prefetches.attempts IS
    'Llamadas al buró en este prefetch, incluidos los reintentos por falla transitoria';
