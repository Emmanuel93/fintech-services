--liquibase formatted sql
--changeset sales-org:007-distributor-nodes author:system
-- Ejecutivos y distribuidores son NODOS del árbol (no solo asignaciones): así el alcance —cartera y
-- dashboard— baja hasta ellos con la MISMA consulta LTREE de subárbol. `party_ref` enlaza el nodo con
-- su persona: staffUserId para EXECUTIVE, partyId del distribuidor para DISTRIBUTOR; null en las
-- unidades estructurales.
ALTER TABLE sales_org.org_units ADD COLUMN party_ref UUID;
CREATE INDEX idx_org_units_party_ref ON sales_org.org_units (party_ref) WHERE party_ref IS NOT NULL;

-- Extiende la escalera estándar por debajo de sucursal para el crédito de distribuidora:
-- SUCURSAL(3) → EJECUTIVO(4) → DISTRIBUIDOR(5). El beneficiario NO es un nivel (es relación de
-- crédito, en commission). El invariante padre.depth = hijo.depth-1 hace cumplir que un distribuidor
-- solo cuelga de un ejecutivo, y un ejecutivo solo de una sucursal.
INSERT INTO sales_org.org_levels (level_id, depth, code, name) VALUES
    ('11111111-0000-0000-0000-000000000004', 4, 'EXECUTIVE',   'Ejecutivo'),
    ('11111111-0000-0000-0000-000000000005', 5, 'DISTRIBUTOR', 'Distribuidor');
