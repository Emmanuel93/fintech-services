--liquibase formatted sql
--changeset credit-portfolio:022-verificacion-de-personas author:system
--comment El veredicto de KYC/AML de cada persona, proyectado, para poder frenar una disposición.

-- ─────────────────────────────────────────────────────────────────────────────
-- Por qué la comprobación va TAMBIÉN en la disposición y no sólo al originar
--
-- Originar ocurre una vez. **Disponer ocurre muchas veces durante años** — una línea revolvente
-- dispone cada mes, una de distribuidor cada colocación—. Y las listas cambian entre una y otra:
-- alguien despejado al abrir la línea puede estar publicado seis meses después, justo antes de que
-- salga dinero.
--
-- Así que la originación es un control de punto en el tiempo y **la disposición es el recurrente**.
-- Es el último momento antes de que el dinero salga, y por tanto el que de verdad protege.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- Por qué proyección local y no una llamada a party
--
-- Porque está en el camino del dinero. Una llamada síncrona añadiría una dependencia que puede no
-- contestar en el peor momento, y fallar cerrado ahí —lo correcto— significaría que un servicio
-- caído detiene todos los desembolsos. Con proyección, el veredicto vigente siempre está aquí:
-- compliance lo republica en cada cambio, y comprobar cuesta una lectura local.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE credit_portfolio.party_verifications (
    party_id          UUID         PRIMARY KEY,
    identity_status   VARCHAR(20)  NOT NULL,
    screening_status  VARCHAR(20)  NOT NULL,
    allows_to_proceed BOOLEAN      NOT NULL,
    block_reason      VARCHAR(200),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE credit_portfolio.party_verifications IS
    'Proyección de compliance.verification-completed. Fuente de verdad: compliance (D16). '
    'Una fila por persona, sobrescrita en cada cambio — no interesa el historial, sólo lo vigente: '
    'lo que pasó queda en el expediente de la propia verificación, con autor y fecha.';

COMMENT ON COLUMN credit_portfolio.party_verifications.allows_to_proceed IS
    'Las DOS cosas: identidad verificada y listas despejadas. Se guarda calculado para que quien '
    'frena una disposición no tenga que repetir la conjunción — y para que añadir un estado nuevo '
    'no deje pasar a nadie por caer fuera de una lista de bloqueos.';

-- La cola operativa: quién está frenando desembolsos ahora mismo.
CREATE INDEX idx_party_verifications_bloqueadas
    ON credit_portfolio.party_verifications (allows_to_proceed)
    WHERE allows_to_proceed = FALSE;
