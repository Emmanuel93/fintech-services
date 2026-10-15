--liquibase formatted sql
--changeset compliance:005-detalle-por-actualizacion author:system
--comment Qué cambió entre una versión de lista y la anterior: el rastro de auditoría de la lista.

-- ─────────────────────────────────────────────────────────────────────────────
-- Sin esto, «¿qué cambió en la actualización del día 14?» sólo se puede contestar comparando medio
-- millón de filas a mano. Y es la pregunta que de verdad se hace:
--
--   «¿por qué esta persona quedó señalada el 14?»
--       → porque esa entrada se AÑADIÓ en la versión publicada el 3
--   «¿por qué dejó de estarlo?»
--       → porque se RETIRÓ en la siguiente
--
-- Es el rastro de la lista, distinto del rastro de la persona: el veredicto dice qué se decidió
-- sobre alguien; esto dice qué decía la fuente y desde cuándo.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- Cómo se reconoce «la misma entrada» entre dos versiones
--
-- Las publicaciones de gobierno **casi nunca traen un identificador estable**, así que la identidad
-- se deriva del contenido y se guarda al cargar:
--
--   identity_key  = quién es      → nombre normalizado + CURP + RFC
--   content_hash  = qué dice      → todos los campos
--
-- Con las dos, un cambio de datos (corrigieron la fecha de nacimiento) sale como MODIFIED en vez de
-- como un alta y una baja, que es lo que pasaría comparando el contenido entero. La contrapartida
-- honesta: si el publicador corrige el **nombre**, cambia la identidad y sí se ve como baja + alta.
-- No hay forma de evitarlo sin un identificador que el publicador no da.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE compliance.watchlist_entries
    ADD COLUMN identity_key VARCHAR(64),
    ADD COLUMN content_hash VARCHAR(64);

CREATE INDEX idx_we_identity_key ON compliance.watchlist_entries (version_id, identity_key);

CREATE TABLE compliance.watchlist_version_changes (
    change_id           UUID         PRIMARY KEY,
    version_id          UUID         NOT NULL REFERENCES compliance.watchlist_versions(version_id),
    previous_version_id UUID         REFERENCES compliance.watchlist_versions(version_id),
    change_type         VARCHAR(20)  NOT NULL,
    identity_key        VARCHAR(64),
    listed_name         VARCHAR(400),
    program             VARCHAR(200),
    detail              VARCHAR(400),

    CONSTRAINT chk_wvc_type CHECK (change_type IN ('ADDED','REMOVED','MODIFIED','INITIAL_LOAD'))
);

COMMENT ON TABLE compliance.watchlist_version_changes IS
    'Qué cambió respecto a la versión anterior de la MISMA lista. La primera carga de una lista no '
    'se detalla entrada por entrada —sería medio millón de filas diciendo «todo es nuevo»— sino con '
    'una sola fila INITIAL_LOAD: es la línea base, y lo que un revisor quiere ver son los cambios.';

CREATE INDEX idx_wvc_version ON compliance.watchlist_version_changes (version_id, change_type);
CREATE INDEX idx_wvc_identity ON compliance.watchlist_version_changes (identity_key)
    WHERE identity_key IS NOT NULL;
