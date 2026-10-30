--liquibase formatted sql
--changeset beneficiary:008-kyc-publico author:system
--comment Fase 3: la liga, la sesión de invitado, las capturas del expediente y la constancia de consentimientos.

-- ─────────────────────────────────────────────────────────────────────────────
-- Desviación consciente del plan (§5, §6.2): el OTP, el rate limit y la sesión de invitado iban
-- en Redis. Van aquí, en Postgres, por dos razones:
--
--   1. El servicio no tiene una sola línea de código sobre Redis. Meterlo para esto sería estrenar
--      una dependencia de infraestructura en el punto más delicado del servicio.
--   2. Lo que se guarda es evidencia. Cuántos códigos se mandaron, cuántos se teclearon mal y cuándo
--      se congeló la liga son el rastro de que la liga se defendió; en Redis se evaporaría con su TTL.
--      Además, en Postgres se puede bloquear la fila de la liga para serializar a dos pestañas que
--      verifican o firman a la vez — que es lo que garantiza un solo prospecto por expediente.
--
-- El volumen no lo justifica al revés: es una beneficiaria por colocación, no tráfico de app.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE beneficiary.placement_invites (
    invite_id              UUID         PRIMARY KEY,
    placement_id           UUID         NOT NULL REFERENCES beneficiary.placements (placement_id),
    -- SHA-256 del token, nunca el token: una copia de la tabla no debe bastar para abrir una liga.
    token_hash             CHAR(64)     NOT NULL,
    expires_at             TIMESTAMPTZ  NOT NULL,
    revoked_at             TIMESTAMPTZ,
    redeemed_at            TIMESTAMPTZ,
    -- El OTP vigente (hash con la liga como sal). Nulo cuando no hay ninguno pendiente.
    otp_hash               CHAR(64),
    otp_expires_at         TIMESTAMPTZ,
    otp_send_count         INTEGER      NOT NULL DEFAULT 0,
    otp_window_started_at  TIMESTAMPTZ,
    failed_attempts        INTEGER      NOT NULL DEFAULT 0,
    frozen_until           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_placement_invites_token UNIQUE (token_hash),
    CONSTRAINT chk_invite_otp_complete CHECK ((otp_hash IS NULL) = (otp_expires_at IS NULL)),
    CONSTRAINT chk_invite_counters CHECK (otp_send_count >= 0 AND failed_attempts >= 0)
);

-- Una sola liga viva por colocación. Rotar revoca la anterior antes de acuñar la nueva; sin este
-- índice, un descuido dejaría dos ligas abriendo el mismo expediente.
CREATE UNIQUE INDEX uq_placement_invites_live
    ON beneficiary.placement_invites (placement_id)
    WHERE revoked_at IS NULL AND redeemed_at IS NULL;

COMMENT ON TABLE beneficiary.placement_invites IS
    'La liga de la beneficiaria. Un solo uso para producir expediente (§3.4): se quema al cerrar el KYC.';

CREATE TABLE beneficiary.kyc_sessions (
    session_id          UUID         PRIMARY KEY,
    placement_id        UUID         NOT NULL REFERENCES beneficiary.placements (placement_id),
    invite_id           UUID         NOT NULL REFERENCES beneficiary.placement_invites (invite_id),
    session_token_hash  CHAR(64)     NOT NULL,
    -- Deslizante: cada paso la renueva 15 minutos, sin pasar del vencimiento de la liga.
    expires_at          TIMESTAMPTZ  NOT NULL,
    closed_at           TIMESTAMPTZ,
    -- El OTP con el que se abrió. Al enviar se vuelve a teclear: es la firma del expediente.
    verified_otp_hash   CHAR(64),
    -- «Tus datos» en JSON. TEXT y no JSONB: nadie consulta adentro, sólo se guarda y se relee.
    captured_data       TEXT,
    -- Se escribe antes de seguir con el cierre, para que un reintento reuse el prospecto.
    prospect_id         UUID,
    ip                  VARCHAR(45),
    user_agent          VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_kyc_sessions_token UNIQUE (session_token_hash)
);

CREATE INDEX idx_kyc_sessions_placement ON beneficiary.kyc_sessions (placement_id, created_at DESC);

CREATE TABLE beneficiary.kyc_documents (
    document_id    UUID          PRIMARY KEY,
    placement_id   UUID          NOT NULL REFERENCES beneficiary.placements (placement_id),
    document_type  VARCHAR(20)   NOT NULL,
    content_type   VARCHAR(120)  NOT NULL,
    size_bytes     BIGINT        NOT NULL,
    content        BYTEA         NOT NULL,
    uploaded_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_kyc_document_type CHECK (document_type IN ('INE_FRONT', 'INE_BACK', 'SELFIE')),
    CONSTRAINT chk_kyc_document_size CHECK (size_bytes > 0),
    -- Una por tipo: volver a tomar la foto reemplaza la anterior.
    CONSTRAINT uq_kyc_documents_type UNIQUE (placement_id, document_type)
);

CREATE TABLE beneficiary.consent_records (
    consent_id     UUID          PRIMARY KEY,
    placement_id   UUID          NOT NULL REFERENCES beneficiary.placements (placement_id),
    -- La sesión es la referencia de verificación OTP: sólo existe detrás de un código a su celular.
    session_id     UUID          NOT NULL REFERENCES beneficiary.kyc_sessions (session_id),
    consent_type   VARCHAR(30)   NOT NULL,
    text_version   VARCHAR(40)   NOT NULL,
    accepted_at    TIMESTAMPTZ   NOT NULL,
    -- VARCHAR y no INET: el valor es evidencia tal como llegó en X-Forwarded-For, y un INET
    -- rechazaría justo el caso raro que más vale conservar para investigarlo.
    ip_address     VARCHAR(45),
    user_agent     VARCHAR(500),
    CONSTRAINT chk_consent_type
        CHECK (consent_type IN ('PRIVACY_NOTICE', 'BUREAU_AUTHORIZATION', 'CREDIT_OWNERSHIP')),
    CONSTRAINT uq_consent_per_session UNIQUE (session_id, consent_type)
);

CREATE INDEX idx_consent_records_placement ON beneficiary.consent_records (placement_id);

COMMENT ON TABLE beneficiary.consent_records IS
    'La constancia. Append-only: qué aceptó, qué versión del texto, cuándo y desde dónde.';
