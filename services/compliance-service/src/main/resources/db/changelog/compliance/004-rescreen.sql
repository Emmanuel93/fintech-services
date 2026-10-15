--liquibase formatted sql
--changeset compliance:004-rescreen author:system
--comment El re-cruce periódico y los datos que necesita para poder correr.

-- ─────────────────────────────────────────────────────────────────────────────
-- Los datos con los que se cruzó, guardados junto al veredicto.
--
-- Sin ellos el re-cruce no puede correr: reconstruir el sujeto desde el veredicto es imposible
-- porque el veredicto no dice a quién se buscó. Y son también lo que hace auditable la decisión —
-- «se buscó ESTE nombre contra ESA versión de lista» — en vez de tener que confiar en que el
-- nombre que la persona tiene hoy es el que se usó entonces.
--
-- Son estables por naturaleza: el nombre y la fecha de nacimiento de una persona no cambian, y si
-- cambiaran (corrección de un dato mal capturado) lo correcto es volver a cruzar con el nuevo — que
-- es justo lo que esta tabla permite.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE compliance.person_verifications
    ADD COLUMN subject_full_name     VARCHAR(400),
    ADD COLUMN subject_date_of_birth DATE,
    ADD COLUMN subject_curp          VARCHAR(18),
    ADD COLUMN subject_rfc           VARCHAR(13),
    ADD COLUMN subject_country       VARCHAR(2);

COMMENT ON COLUMN compliance.person_verifications.subject_full_name IS
    'El nombre con el que se cruzó, crudo. La forma normalizada se deriva; guardar la cruda permite '
    'repetir el cruce si la normalización cambia, que es la única forma de no perder coincidencias '
    'en silencio cuando eso pase.';

-- ─────────────────────────────────────────────────────────────────────────────
-- Las corridas de re-cruce.
--
-- El negocio asumió la ventana entre que se activa una lista y que termina el re-cruce. Un riesgo
-- asumido sin constancia es indistinguible de un descuido: la pregunta de una revisión no es «¿se
-- corren re-cruces?» sino «¿cuándo corrió el que debía detectar esto y cuánto tardó?».
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE compliance.rescreen_runs (
    run_id                  UUID         PRIMARY KEY,
    triggered_by_version_id UUID,
    trigger                 VARCHAR(20)  NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    total_screened          INTEGER      NOT NULL DEFAULT 0,
    changed_count           INTEGER      NOT NULL DEFAULT 0,
    failure_reason          VARCHAR(500),
    started_at              TIMESTAMPTZ  NOT NULL,
    finished_at             TIMESTAMPTZ,

    CONSTRAINT chk_rr_status  CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    CONSTRAINT chk_rr_trigger CHECK (trigger IN ('LIST_ACTIVATED','SCHEDULED','MANUAL')),
    -- Una corrida fallida tiene que decir por qué: «falló» sin motivo obliga a reproducirlo.
    CONSTRAINT chk_rr_failure CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL),
    CONSTRAINT chk_rr_terminal CHECK (status = 'RUNNING' OR finished_at IS NOT NULL)
);

-- «¿Cuándo fue el último re-cruce?» es la primera pregunta de cualquier revisión, y la que
-- responde si el control está vivo o se apagó hace meses sin que nadie lo notara.
CREATE INDEX idx_rescreen_runs_recientes
    ON compliance.rescreen_runs (started_at DESC);
