--liquibase formatted sql
--changeset compliance:007-raiz-de-curp author:system
--comment La raíz derivada del CURP: el cruce por documento para las entradas que no traen documento.

-- ─────────────────────────────────────────────────────────────────────────────
-- El CURP es el identificador que una persona tiene desde que nace, y sus primeros diez caracteres
-- NO los asigna nadie: se CALCULAN de los apellidos, el nombre y la fecha de nacimiento.
--
-- Eso permite algo que ningún otro dato permite: reconstruirlos para las entradas de lista que no
-- publican CURP — que, medido sobre las fuentes reales, son la mayoría. `SAT-69B` ni siquiera tiene
-- columna de CURP; en `OFAC-SDN` lo trae una de cada tres entradas.
--
-- Se guardan DOS raíces por entrada porque las listas no coinciden en el orden: unas escriben
-- «APELLIDO APELLIDO NOMBRE» y otras al revés, a veces dentro del mismo archivo. Derivar las dos
-- lecturas y comparar ambas cuesta dos cadenas de diez caracteres; adivinar cuál usó el publicador
-- cuesta las coincidencias que se pierden cuando se adivina mal.
--
-- Sólo se llenan cuando la entrada NO trae CURP: si lo trae, el cruce por documento ya es una
-- identificación y añadirle una raíz derivada sólo agregaría evidencia más débil sobre algo resuelto.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE compliance.watchlist_entries
    ADD COLUMN curp_root_a VARCHAR(10),
    ADD COLUMN curp_root_b VARCHAR(10);

COMMENT ON COLUMN compliance.watchlist_entries.curp_root_a IS
    'Raíz del CURP derivada leyendo «APELLIDO APELLIDO NOMBRE». Es una hipótesis fuerte, no una '
    'identificación: los caracteres que RENAPO asigna no son derivables.';

COMMENT ON COLUMN compliance.watchlist_entries.curp_root_b IS
    'La misma raíz leyendo «NOMBRE APELLIDO APELLIDO», porque las listas mezclan los dos órdenes.';

-- Dos índices y no uno compuesto: la consulta pregunta por CUALQUIERA de las dos columnas con un
-- `OR`, y eso Postgres lo resuelve combinando dos índices con un bitmap. Un índice compuesto
-- (a, b) no serviría para buscar por `b`.
--
-- Parciales porque la mayoría de las filas tendrán las dos en NULL —las que sí traen CURP, y las que
-- no traen fecha de nacimiento—, y un índice que no las guarda es más pequeño y más rápido.
CREATE INDEX idx_we_curp_root_a ON compliance.watchlist_entries (curp_root_a)
    WHERE curp_root_a IS NOT NULL;

CREATE INDEX idx_we_curp_root_b ON compliance.watchlist_entries (curp_root_b)
    WHERE curp_root_b IS NOT NULL;

-- Y la medida de lo que la derivación recupera, junto a las otras tres coberturas.
--
-- Sin este número, «ahora cruzamos por raíz» es una afirmación sin respaldo: si la lista tampoco
-- trae fecha de nacimiento, no se deriva ni una raíz y el cruce nuevo no encuentra nada. La resta
-- entre el alcance y la cobertura publicada es exactamente lo que se ganó.
ALTER TABLE compliance.watchlist_versions
    ADD COLUMN entries_with_curp_root INTEGER;

COMMENT ON COLUMN compliance.watchlist_versions.entries_with_curp_root IS
    'Cuántas entradas SIN CURP publicado ganaron una raíz derivada. Sumado a entries_with_curp y '
    'dividido entre accepted_rows da el alcance real del cruce por CURP sobre esta versión.';
