--liquibase formatted sql
--changeset sales-org:002-create-org-levels author:system
-- La escalera de niveles es DATO, no un enum en código: agregar o insertar un nivel es una fila,
-- no un despliegue. `depth` es el ordinal de la escalera (0 = raíz). Único: dos niveles no comparten
-- posición. Insertar un nivel intermedio corre los depth siguientes (lógica de aplicación).
CREATE TABLE IF NOT EXISTS sales_org.org_levels (
    level_id    UUID         NOT NULL,
    depth       INT          NOT NULL,
    code        VARCHAR(40)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_org_levels PRIMARY KEY (level_id),
    CONSTRAINT uq_org_levels_depth UNIQUE (depth) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_org_levels_code  UNIQUE (code),
    CONSTRAINT ck_org_levels_depth_nonneg CHECK (depth >= 0)
);
