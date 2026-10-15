--liquibase formatted sql
--changeset party:012-verificacion-por-persona author:system
--comment El veredicto de KYC y listas de la persona, proyectado desde compliance (D16).

-- ─────────────────────────────────────────────────────────────────────────────
-- Una persona se verifica UNA vez, al alta, sin importar su rol.
--
-- Antes el único veredicto vivía dentro de la colocación (`beneficiary.placements.identity_decision`),
-- lo que significaba dos cosas malas a la vez: la misma persona podía tener juicios distintos sobre
-- su identidad según por dónde hubiera entrado, y el titular de uso propio no tenía **ninguno**,
-- porque no hay colocación.
--
-- `party` es dueño de la persona, así que aquí vive el veredicto y aquí lo consultan las compuertas.
-- Quien lo produce es `compliance`, que es quien habla con proveedores y tiene las listas.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE party.parties
    ADD COLUMN identity_status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    ADD COLUMN screening_status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    ADD COLUMN verification_block_reason VARCHAR(200),
    ADD COLUMN verified_at            TIMESTAMPTZ;

-- **El default es PENDING y PENDING bloquea.**
--
-- Es deliberado y es lo contrario de lo cómodo: todas las filas existentes quedan bloqueadas hasta
-- que se verifiquen. Un default permisivo habría dejado a toda la cartera histórica marcada como
-- comprobada sin que nadie la comprobara — que es exactamente el dato que un revisor pediría.
ALTER TABLE party.parties
    ADD CONSTRAINT chk_parties_identity_status
        CHECK (identity_status IN ('PENDING','IN_REVIEW','VERIFIED','REJECTED')),
    ADD CONSTRAINT chk_parties_screening_status
        CHECK (screening_status IN ('PENDING','CLEARED','POTENTIAL_MATCH','CONFIRMED_MATCH'));

COMMENT ON COLUMN party.parties.identity_status IS
    '¿Es quien dice ser? Lo resuelve la persona con mejor evidencia si falla.';

COMMENT ON COLUMN party.parties.screening_status IS
    '¿Está en listas? La persona NO puede hacer nada si falla: lo adjudica cumplimiento. Por eso es '
    'un campo aparte y no una sola bandera «verificado».';

COMMENT ON COLUMN party.parties.verification_block_reason IS
    'Por qué no pasa, en texto. Existe para que quien consulta no tenga que deducirlo combinando '
    'los dos estados — y para que el mensaje al cliente pueda ser el correcto en cada caso.';

-- La cola operativa: quién está esperando que alguien lo resuelva.
CREATE INDEX idx_parties_pendientes_de_verificacion
    ON party.parties (identity_status, screening_status)
    WHERE identity_status <> 'VERIFIED' OR screening_status <> 'CLEARED';
