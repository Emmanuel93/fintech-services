-- D2 Scoring | Precalificación: caché de resultados por prospecto (todos los productos B2C
-- elegibles en un solo snapshot), para que el home de la app pueda mostrar "créditos
-- disponibles" sin re-correr el motor de reglas en cada carga. Una fila por prospecto —
-- se sobreescribe (refresh) cuando el snapshot expira (TTL verificado en el service layer).

CREATE TABLE scoring.prequalification_snapshots (
    snapshot_id   UUID        NOT NULL,
    prospect_id   UUID        NOT NULL,
    prospect_type VARCHAR(20) NOT NULL,
    report_id     UUID,       -- NULL cuando aún no hay CirculoReport (prospecto recién registrado)
    results       JSONB       NOT NULL,
    computed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_prequalification_snapshots PRIMARY KEY (snapshot_id),
    CONSTRAINT uq_prequalification_snapshots_prospect UNIQUE (prospect_id)
    -- report_id es un UUID plano sin FK, mismo criterio que score_evaluations.report_id
    -- (trazabilidad, no integridad referencial estricta entre agregados).
);

CREATE INDEX idx_prequalification_snapshots_prospect
    ON scoring.prequalification_snapshots (prospect_id);

COMMENT ON TABLE scoring.prequalification_snapshots IS
    'Caché por prospecto del resultado de evaluar todos los productos B2C elegibles, para el home "créditos disponibles". No escribe score_evaluations ni publica scoring.scoring-completed — es de solo lectura sobre el motor de reglas.';
COMMENT ON COLUMN scoring.prequalification_snapshots.results IS
    'JSON array: [{productType, evaluated, skippedReason, decision, riskLevel, totalScore}]';
