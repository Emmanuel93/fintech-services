--liquibase formatted sql
--changeset compliance:008-bitacora-de-consultas author:system
--comment Cada consulta a listas, tal como ocurrió. Append-only: es el rastro que pide la norma.

-- ─────────────────────────────────────────────────────────────────────────────
-- `person_verifications` guarda el veredicto VIGENTE y se sobrescribe: cada re-cruce pisa el
-- anterior. Eso contesta «¿puede operar hoy?» y no contesta lo que pregunta una revisión:
--
--   «El 3 de septiembre le dieron un crédito a esta persona que HOY aparece en una lista de
--    sancionados. ¿La consultaron? ¿Cuándo? ¿Qué decía la lista ESE día?»
--
-- Con sólo el veredicto vigente la respuesta es «hoy está señalada», que no distingue
-- «no lo sabíamos» de «lo dejamos pasar». Y ésa es la distinción por la que se responde.
--
-- Esta tabla guarda cada consulta: cuándo, con qué datos, contra qué versiones de lista y qué salió.
-- Se escribe también cuando nada cambió — lo que hay que demostrar es QUE SE MIRÓ, y un re-cruce que
-- confirma que alguien sigue limpio es diligencia ejecutada: sin rastro es indistinguible de no
-- haberlo corrido.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE compliance.screening_checks (
    check_id               UUID         PRIMARY KEY,

    -- La llave que comparten compliance, party y los flujos de alta. El party_id puede faltar: el
    -- primer cruce ocurre antes de que exista el Party, porque los dos cuelgan del mismo evento.
    prospect_id            UUID         NOT NULL,
    party_id               UUID,

    trigger_reason         VARCHAR(20)  NOT NULL,
    result                 VARCHAR(20)  NOT NULL,

    candidate_count        INTEGER      NOT NULL DEFAULT 0,
    strongest_reason       VARCHAR(30),
    strongest_listed_name  VARCHAR(400),

    -- Contra qué versiones se consultó. **NULL significa que no había ninguna vigente**, y eso es un
    -- hecho distinto de «salió limpia»: es «no se comprobó nada». Sin esta columna el resultado no
    -- es reproducible — decir «salió limpia» sin decir contra qué lista no sustenta nada, porque la
    -- lista de ese día ya no es la de hoy.
    list_versions          VARCHAR(1000),

    -- Los datos CON LOS QUE se consultó. Si la persona corrigió su nombre después, el resultado de
    -- entonces sólo se explica con el nombre de entonces; sin esto, una consulta vieja parecería
    -- mal hecha.
    subject_full_name      VARCHAR(400),
    subject_curp           VARCHAR(18),
    subject_rfc            VARCHAR(13),
    subject_date_of_birth  DATE,

    checked_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_sc_trigger CHECK (trigger_reason IN ('ONBOARDING','RESCREEN','ON_DEMAND')),
    CONSTRAINT chk_sc_result  CHECK (result IN ('PENDING','CLEARED','POTENTIAL_MATCH','CONFIRMED_MATCH'))
);

COMMENT ON TABLE compliance.screening_checks IS
    'Bitácora append-only de consultas a listas. No se actualiza ni se borra: el código no tiene '
    'mutadores ni sentencias UPDATE sobre ella. Retención 10 años (LFPIORPI, registros AML).';

COMMENT ON COLUMN compliance.screening_checks.list_versions IS
    'Versiones de lista contra las que se consultó. NULL = no había ninguna vigente, que es '
    '«no se comprobó nada», no «salió limpia».';

COMMENT ON COLUMN compliance.screening_checks.trigger_reason IS
    'ONBOARDING (al dar de alta: titular, distribuidor o beneficiaria) · RESCREEN (lista nueva) · '
    'ON_DEMAND (alguien la pidió). Cambia lo que la consulta demuestra.';

-- El acceso natural de una auditoría: el historial de UNA persona, lo más reciente primero.
CREATE INDEX idx_sc_prospecto ON compliance.screening_checks (prospect_id, checked_at DESC);

-- Y el de un informe: todo lo consultado en un período. Sin él, «qué cruzamos en septiembre» es un
-- escaneo completo de una tabla que crece con cada re-cruce de toda la cartera.
CREATE INDEX idx_sc_fecha ON compliance.screening_checks (checked_at DESC);

-- Las que encontraron algo, que son las pocas que alguien va a mirar de verdad.
CREATE INDEX idx_sc_con_coincidencia ON compliance.screening_checks (result, checked_at DESC)
    WHERE result <> 'CLEARED';
