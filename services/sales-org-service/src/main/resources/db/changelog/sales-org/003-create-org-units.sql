--liquibase formatted sql
--changeset sales-org:003-create-org-units author:system
-- Una unidad es un nodo del árbol comercial (una sucursal, una zona, una región...). `path` es su
-- ruta materializada (labels = códigos de unidad, p.ej. MX.NORTE.MTY.SUC001), guardada como texto.
-- El índice GIST funcional sobre (path::ltree) resuelve "todo el subárbol de X" (path <@ :ancestor)
-- en una sola consulta indexada, sin exponer el tipo ltree a Hibernate.
CREATE TABLE IF NOT EXISTS sales_org.org_units (
    unit_id        UUID         NOT NULL,
    level_id       UUID         NOT NULL,
    parent_unit_id UUID,
    code           VARCHAR(40)  NOT NULL,
    name           VARCHAR(160) NOT NULL,
    path           TEXT         NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by     VARCHAR(120),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_org_units PRIMARY KEY (unit_id),
    CONSTRAINT uq_org_units_code UNIQUE (code),
    CONSTRAINT uq_org_units_path UNIQUE (path),
    CONSTRAINT fk_org_units_level  FOREIGN KEY (level_id)       REFERENCES sales_org.org_levels (level_id),
    CONSTRAINT fk_org_units_parent FOREIGN KEY (parent_unit_id) REFERENCES sales_org.org_units (unit_id)
);
CREATE INDEX IF NOT EXISTS idx_org_units_path   ON sales_org.org_units USING GIST ((path::public.ltree));
CREATE INDEX IF NOT EXISTS idx_org_units_parent ON sales_org.org_units (parent_unit_id);
CREATE INDEX IF NOT EXISTS idx_org_units_level  ON sales_org.org_units (level_id);
