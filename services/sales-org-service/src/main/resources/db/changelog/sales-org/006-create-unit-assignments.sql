--liquibase formatted sql
--changeset sales-org:006-create-unit-assignments author:system
-- Quién pertenece a qué unidad. Append-only: reasignar NO actualiza la fila, cierra la vigente
-- (ended_at) y agrega una nueva. Así queda el historial completo y "la unidad actual de X" es la
-- fila activa. `assignee_type` generaliza para que los distribuidores (B2B2C, más adelante) usen la
-- misma tabla sin cambio de esquema.
CREATE TABLE sales_org.unit_assignments (
    assignment_id   UUID        NOT NULL,
    unit_id         UUID        NOT NULL,
    assignee_type   VARCHAR(20) NOT NULL,                 -- STAFF | DISTRIBUTOR
    assignee_id     UUID        NOT NULL,
    assignment_role VARCHAR(40) NOT NULL DEFAULT 'MEMBER', -- MEMBER | MANAGER
    assigned_by     VARCHAR(120),
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    CONSTRAINT pk_unit_assignments PRIMARY KEY (assignment_id),
    CONSTRAINT fk_unit_assignments_unit FOREIGN KEY (unit_id) REFERENCES sales_org.org_units (unit_id)
);

-- Un asignado tiene a lo más UNA asignación activa (su unidad actual). Índice único parcial: solo
-- aplica a las filas vigentes, así el historial cerrado no estorba.
CREATE UNIQUE INDEX uq_unit_assignments_active_assignee
    ON sales_org.unit_assignments (assignee_type, assignee_id) WHERE ended_at IS NULL;

CREATE INDEX idx_unit_assignments_unit_active
    ON sales_org.unit_assignments (unit_id) WHERE ended_at IS NULL;
CREATE INDEX idx_unit_assignments_assignee
    ON sales_org.unit_assignments (assignee_type, assignee_id);
