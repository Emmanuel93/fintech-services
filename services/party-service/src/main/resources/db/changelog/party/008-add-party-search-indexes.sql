-- changeset fintech:008-add-party-search-indexes
-- Búsqueda de clientes para el backoffice: por nombre, CURP o RFC.
--
-- El backoffice pregunta por población ("dame los García", "el RFC que empieza
-- con GARJ..."), no por UUID. Eso es LIKE '%texto%' sobre varias columnas, y un
-- índice B-tree no sirve para el comodín inicial. pg_trgm + GIN sí acelera el
-- "contains" y es lo que pide el dominio (búsqueda difusa por lo que el operador
-- recuerda). La consulta compara contra LOWER(columna), así que el índice se crea
-- sobre la misma expresión para que el planeador lo pueda usar.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_parties_first_name_trgm ON party.parties USING gin (LOWER(first_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_parties_last_name1_trgm ON party.parties USING gin (LOWER(last_name1) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_parties_last_name2_trgm ON party.parties USING gin (LOWER(last_name2) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_parties_curp_trgm       ON party.parties USING gin (LOWER(curp) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_parties_rfc_trgm        ON party.parties USING gin (LOWER(rfc) gin_trgm_ops);
