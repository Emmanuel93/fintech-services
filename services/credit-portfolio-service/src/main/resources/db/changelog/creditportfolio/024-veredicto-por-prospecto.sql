--liquibase formatted sql
--changeset credit-portfolio:024-veredicto-por-prospecto author:system
--comment El veredicto se proyecta también por prospecto: la cuenta guarda al responsable por su prospectId.

-- ─────────────────────────────────────────────────────────────────────────────
-- Por qué esta tabla estaba vacía y la compuerta de disposición bloqueaba a todo el mundo
--
-- 022 la llavó por `party_id`, y eso dejó dos huecos que juntos la vaciaban:
--
--   1. compliance evalúa a la persona al consumir `origination.prospect-created`, el MISMO evento
--      del que party crea el Party — así que el veredicto sale casi siempre con partyId nulo, y el
--      listener lo descartaba.
--   2. Y aunque llegara con él, `credit_accounts.obligor_party_id` guarda el **prospectId** del
--      titular (comprobado: las 46 cuentas de la base local casan por prospecto y ninguna por
--      party). La compuerta busca al responsable por ese id, así que un veredicto llavado por
--      party nunca casaba con él.
--
-- Resultado: «sin veredicto de verificación — no se dispone» para toda disposición, también para
-- la gente con identidad comprobada y listas limpias. Fallar cerrado era lo correcto; estar
-- cerrado para todos, no.
--
-- La persona se identifica ahora por cualquiera de las dos llaves: prospectId siempre (compliance
-- lo publica en cada veredicto) y partyId cuando ya se conoce (la beneficiaria de una colocación
-- llega a la compuerta por su partyId). Ninguna es la PK: la llave técnica es propia.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE credit_portfolio.party_verifications DROP CONSTRAINT party_verifications_pkey;

ALTER TABLE credit_portfolio.party_verifications
    ADD COLUMN projection_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN prospect_id   UUID;

ALTER TABLE credit_portfolio.party_verifications ADD PRIMARY KEY (projection_id);
ALTER TABLE credit_portfolio.party_verifications ALTER COLUMN party_id DROP NOT NULL;

CREATE UNIQUE INDEX uq_party_verifications_party
    ON credit_portfolio.party_verifications (party_id) WHERE party_id IS NOT NULL;
CREATE UNIQUE INDEX uq_party_verifications_prospect
    ON credit_portfolio.party_verifications (prospect_id) WHERE prospect_id IS NOT NULL;

-- Una fila sin ninguna de las dos llaves sería un veredicto de nadie.
ALTER TABLE credit_portfolio.party_verifications
    ADD CONSTRAINT chk_party_verifications_some_key
        CHECK (party_id IS NOT NULL OR prospect_id IS NOT NULL);
