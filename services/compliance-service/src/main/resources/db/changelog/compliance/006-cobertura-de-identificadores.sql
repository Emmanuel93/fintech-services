--liquibase formatted sql
--changeset compliance:006-cobertura-de-identificadores author:system
--comment Qué fracción de cada lista trae CURP, RFC o fecha — el dato que decide cómo se puede cruzar.

-- ─────────────────────────────────────────────────────────────────────────────
-- Cruzar por CURP es lo ideal: es una IDENTIFICACIÓN, no un parecido, y no produce homónimos.
--
-- Pero sólo sirve sobre las entradas que **traen** CURP, y eso lo decide el publicador, no nosotros.
-- `SAT-69B` ni siquiera tiene columna de CURP —trae RFC—; una lista de sancionados internacional
-- está llena de extranjeros que no tienen CURP en absoluto.
--
-- Sin medir esto, «siempre cruzamos por CURP» suena a control estricto y puede ser un control que
-- **nunca dispara**: si el 3 % de la lista trae CURP, se está comprobando contra el 3 % de la lista
-- y el 97 % restante sale limpio sin que nada falle.
--
-- Estas columnas convierten esa suposición en un número que se puede mirar en cada carga.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE compliance.watchlist_versions
    ADD COLUMN entries_with_curp INTEGER,
    ADD COLUMN entries_with_rfc  INTEGER,
    ADD COLUMN entries_with_dob  INTEGER;

COMMENT ON COLUMN compliance.watchlist_versions.entries_with_curp IS
    'Cuántas entradas traen CURP. Dividido entre accepted_rows es la cobertura real del cruce por '
    'documento: la fracción de la lista contra la que una consulta por CURP puede encontrar algo.';
