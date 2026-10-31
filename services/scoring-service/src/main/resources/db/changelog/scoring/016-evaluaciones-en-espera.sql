--liquibase formatted sql
--changeset scoring:016-evaluaciones-en-espera
--comment Puntajes que esperan al reporte de buró; se reanudan cuando el prefetch termina.

-- Una solicitud cuyo puntaje llegó antes que el reporte se reintentaba unos 9 s y, agotado eso, se
-- quedaba en PENDING_SCORING para siempre con un warning en el log. Un prefetch contra el buró real,
-- con sus reintentos por falla transitoria, puede tardar casi un minuto: la carrera no la ganaba
-- nadie. Aquí queda la solicitud estacionada hasta que el reporte aterriza.
CREATE TABLE IF NOT EXISTS scoring.pending_evaluations (
    application_id UUID        NOT NULL,
    prospect_id    UUID        NOT NULL,
    prospect_type  VARCHAR(20),
    product_type   VARCHAR(30) NOT NULL,
    reason         VARCHAR(30) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    parked_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resumed_at     TIMESTAMPTZ,
    CONSTRAINT pk_pending_evaluations PRIMARY KEY (application_id),
    CONSTRAINT chk_pending_evaluations_status CHECK (status IN ('WAITING', 'RESUMED'))
);

-- El prefetch busca por prospecto y sólo le interesan las que siguen esperando.
CREATE INDEX IF NOT EXISTS idx_pending_evaluations_waiting
    ON scoring.pending_evaluations (prospect_id) WHERE status = 'WAITING';

COMMENT ON TABLE scoring.pending_evaluations IS
    'Puntajes solicitados antes de que existiera el reporte de buró; el prefetch los reanuda al terminar';
