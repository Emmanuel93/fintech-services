-- changeset fintech:009-add-executive-assignment
-- Ejecutivo de cuenta: qué empleado del backoffice (StaffUser de identity, rol
-- EXECUTIVE) lleva a cada cliente. Es una asignación operativa del backoffice,
-- no un evento del dominio crediticio, así que vive como atributo del party.
-- El nombre se desnormaliza para pintar el listado sin ir a identity por fila.
ALTER TABLE party.parties
    ADD COLUMN IF NOT EXISTS assigned_executive_id   UUID,
    ADD COLUMN IF NOT EXISTS assigned_executive_name VARCHAR(200);

-- El backoffice filtra la cartera/clientes por ejecutivo; sin índice sería un
-- seq scan sobre una tabla que sólo crece.
CREATE INDEX IF NOT EXISTS idx_parties_assigned_executive
    ON party.parties (assigned_executive_id)
    WHERE assigned_executive_id IS NOT NULL;
