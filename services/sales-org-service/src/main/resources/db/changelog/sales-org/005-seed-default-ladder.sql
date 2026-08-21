--liquibase formatted sql
--changeset sales-org:005-seed-default-ladder author:system
-- Escalera estándar de arranque: nacional → región → zona → sucursal. Es configurable (se pueden
-- agregar o insertar niveles desde el backoffice); esto solo la deja poblada para no arrancar en
-- blanco. UUIDs fijos para que la raíz sea referenciable de forma estable.
-- Sin ON CONFLICT: Liquibase corre cada changeset una sola vez (lo rastrea en DATABASECHANGELOG),
-- así que un INSERT plano es idempotente a nivel de despliegue. Además, uq_org_levels_depth es
-- DEFERRABLE (lo exige el corrimiento al insertar niveles) y Postgres no admite una restricción
-- diferible como árbitro de ON CONFLICT.
INSERT INTO sales_org.org_levels (level_id, depth, code, name) VALUES
    ('11111111-0000-0000-0000-000000000000', 0, 'NATIONAL', 'Nacional'),
    ('11111111-0000-0000-0000-000000000001', 1, 'REGION',   'Región'),
    ('11111111-0000-0000-0000-000000000002', 2, 'ZONE',     'Zona'),
    ('11111111-0000-0000-0000-000000000003', 3, 'BRANCH',   'Sucursal');

-- Unidad raíz nacional. Todo el árbol cuelga de aquí; su path es su propio código.
INSERT INTO sales_org.org_units (unit_id, level_id, parent_unit_id, code, name, path, created_by) VALUES
    ('22222222-0000-0000-0000-000000000000',
     '11111111-0000-0000-0000-000000000000',
     NULL, 'MX', 'México (Nacional)', 'MX', 'system');
