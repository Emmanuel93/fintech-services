--liquibase formatted sql
--changeset compliance:002-screening-indexes author:system
--comment El cruce por parecido necesita pg_trgm y su índice; sin él es un escaneo completo por persona.

-- Ya la usan `party` y `credit-portfolio` para sus búsquedas: no se añade una dependencia nueva.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Sin este índice, cada alta de persona compara contra cientos de miles de filas fila por fila.
-- GIN sobre trigramas convierte eso en una búsqueda, que es lo que permite que el screening corra
-- dentro del alta y no como un proceso nocturno.
CREATE INDEX idx_we_normalized_trgm
    ON compliance.watchlist_entries USING gin (normalized_name gin_trgm_ops);
