--liquibase formatted sql
--changeset compliance:001-create-watchlists author:system
--comment D16 · las listas contra las que se comprueba a una persona, versionadas.

CREATE SCHEMA IF NOT EXISTS compliance;

-- ─────────────────────────────────────────────────────────────────────────────
-- Una carga es una VERSIÓN, no una mutación.
--
-- Cargar contra la tabla viva convierte cualquier fallo a mitad de archivo —red, formato, disco— en
-- una ventana donde quien todavía no se insertó sale «limpio». Salir limpio es exactamente lo que
-- el control existe para impedir, y esa ventana no deja rastro.
--
-- Así que se carga en una versión que nadie consulta y sólo se activa cuando termina completa. La
-- anterior queda SUPERSEDED y **no se borra**: un veredicto emitido ayer tiene que poder explicarse
-- contra la lista que se usó ayer.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE compliance.watchlist_versions (
    version_id      UUID         PRIMARY KEY,
    source_code     VARCHAR(40)  NOT NULL,
    kind            VARCHAR(20)  NOT NULL,
    file_hash       VARCHAR(64)  NOT NULL,
    file_name       VARCHAR(255),
    published_on    DATE,
    status          VARCHAR(20)  NOT NULL,
    total_rows      INTEGER      NOT NULL DEFAULT 0,
    accepted_rows   INTEGER      NOT NULL DEFAULT 0,
    rejected_rows   INTEGER      NOT NULL DEFAULT 0,
    failure_reason  VARCHAR(500),
    started_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ,
    activated_at    TIMESTAMPTZ,

    CONSTRAINT chk_wv_kind   CHECK (kind IN ('SANCTIONS','PEP','TAX_BLACKLIST','INTERNAL')),
    CONSTRAINT chk_wv_status CHECK (status IN ('LOADING','LOADED','ACTIVE','SUPERSEDED','FAILED')),
    -- Una versión activa sin fecha de activación no se puede auditar.
    CONSTRAINT chk_wv_activated CHECK (status <> 'ACTIVE' OR activated_at IS NOT NULL),
    -- Y una fallida tiene que decir por qué: «falló» sin motivo no es información.
    CONSTRAINT chk_wv_failure   CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL)
);

-- La idempotencia en la base y no sólo en el código: el mismo archivo no se carga dos veces aunque
-- dos sincronizaciones se solapen. Sin esto, dos disparos concurrentes duplican la lista entera y
-- la comprobación previa del servicio no lo impide — lee antes de que el otro escriba.
CREATE UNIQUE INDEX uq_watchlist_version_hash
    ON compliance.watchlist_versions (file_hash);

-- **Una sola versión vigente por lista.** Es el invariante que hace que «la lista de hoy» sea una
-- pregunta con una respuesta. Índice único parcial: sólo aplica a las ACTIVE.
CREATE UNIQUE INDEX uq_watchlist_active_por_lista
    ON compliance.watchlist_versions (source_code)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_watchlist_versions_source
    ON compliance.watchlist_versions (source_code, started_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- Las filas de la lista, ya normalizadas.
--
-- Se normaliza al CARGAR y no al consultar: si cada consulta normaliza por su cuenta, dos servicios
-- con el mismo nombre obtienen resultados distintos, y el día que cambie la normalización los
-- nombres guardados dejan de corresponder con los buscados — sin que nada falle.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE compliance.watchlist_entries (
    entry_id        UUID         PRIMARY KEY,
    version_id      UUID         NOT NULL REFERENCES compliance.watchlist_versions(version_id),
    kind            VARCHAR(20)  NOT NULL,
    raw_name        VARCHAR(400) NOT NULL,
    normalized_name VARCHAR(400) NOT NULL,
    sorted_name     VARCHAR(400) NOT NULL,
    curp            VARCHAR(18),
    rfc             VARCHAR(13),
    date_of_birth   DATE,
    country         VARCHAR(2),
    program         VARCHAR(200),
    source_line     INTEGER      NOT NULL,

    CONSTRAINT chk_we_kind CHECK (kind IN ('SANCTIONS','PEP','TAX_BLACKLIST','INTERNAL'))
);

COMMENT ON COLUMN compliance.watchlist_entries.raw_name IS
    'El nombre tal como lo publicó la lista. Se conserva para mostrárselo a quien adjudica: la '
    'forma normalizada no sirve para que una persona reconozca a otra.';

COMMENT ON COLUMN compliance.watchlist_entries.sorted_name IS
    'Los mismos tokens en orden alfabético. Las listas no coinciden en el orden de nombre y '
    'apellidos —ni siquiera dentro del mismo archivo— y comparar la cadena tal cual falla con la '
    'misma persona escrita al revés.';

COMMENT ON COLUMN compliance.watchlist_entries.source_line IS
    'Número de línea en el archivo de origen. Sin esto, revisar por qué alguien está o no está en '
    'la lista es buscar a ciegas en un archivo de cientos de miles de filas.';

CREATE INDEX idx_we_version        ON compliance.watchlist_entries (version_id);
CREATE INDEX idx_we_normalized     ON compliance.watchlist_entries (version_id, normalized_name);
CREATE INDEX idx_we_sorted         ON compliance.watchlist_entries (version_id, sorted_name);

-- CURP y RFC son identificaciones; el nombre es una sospecha. Se indexan aparte para que el cruce
-- pueda afirmar cuál de las dos cosas encontró.
CREATE INDEX idx_we_curp ON compliance.watchlist_entries (curp) WHERE curp IS NOT NULL;
CREATE INDEX idx_we_rfc  ON compliance.watchlist_entries (rfc)  WHERE rfc  IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Lo que no se pudo usar.
--
-- Se guarda y no sólo se cuenta: «se rechazaron 812 filas» no permite arreglar nada; «la línea
-- 4 417 no traía nombre» sí. Es la diferencia entre saber que el layout cambió y saber qué cambió.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE compliance.watchlist_row_rejections (
    rejection_id UUID         PRIMARY KEY,
    version_id   UUID         NOT NULL REFERENCES compliance.watchlist_versions(version_id),
    source_line  INTEGER      NOT NULL,
    reason       VARCHAR(200) NOT NULL,
    raw_line     VARCHAR(400)
);

CREATE INDEX idx_wrr_version ON compliance.watchlist_row_rejections (version_id, source_line);
