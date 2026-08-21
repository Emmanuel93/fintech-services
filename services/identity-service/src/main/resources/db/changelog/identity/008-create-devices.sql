--liquibase formatted sql

--changeset identity:008-create-devices author:system
--comment Tabla de dispositivos registrados por party. Soporta detección de dispositivos nuevos, políticas de confianza y análisis forense.

CREATE TABLE IF NOT EXISTS identity.devices (
    id                UUID         NOT NULL,
    party_id          UUID         NOT NULL,
    client_device_id  VARCHAR(255) NOT NULL,

    -- Metadata de clasificación del dispositivo (derivada del User-Agent)
    platform          VARCHAR(20),          -- IOS | ANDROID | WEB | DESKTOP | API | UNKNOWN
    os                VARCHAR(100),         -- "iOS 17.4", "Android 14", "Windows 11"
    browser           VARCHAR(100),         -- "Chrome 124", "Safari 17", "Firefox 125"
    model             VARCHAR(100),         -- "iPhone 15 Pro", "Samsung Galaxy S23"
    user_agent        TEXT,                 -- User-Agent completo del último acceso

    -- Trazabilidad temporal y de red
    first_seen_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    first_seen_ip     VARCHAR(45),          -- IP del primer acceso (IPv4/IPv6)
    last_seen_ip      VARCHAR(45),          -- IP del último acceso

    -- Control y política
    trusted           BOOLEAN              NOT NULL DEFAULT false,
    status            VARCHAR(20)          NOT NULL DEFAULT 'ACTIVE',

    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_devices PRIMARY KEY (id),
    CONSTRAINT uq_device_party_client_id UNIQUE (party_id, client_device_id),
    CONSTRAINT chk_device_status CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

CREATE INDEX IF NOT EXISTS idx_devices_party_id
    ON identity.devices (party_id);

CREATE INDEX IF NOT EXISTS idx_devices_last_seen_at
    ON identity.devices (last_seen_at DESC);

COMMENT ON TABLE identity.devices IS
    'Dispositivos registrados por party. Un registro por (party_id, client_device_id). '
    'Se crea al primer login y se actualiza (last_seen_at, last_seen_ip) en cada autenticación posterior.';

COMMENT ON COLUMN identity.devices.client_device_id IS
    'ID opaco enviado por el cliente — UUID generado en instalación de la app móvil, '
    'fingerprint de navegador o identificador de SDK.';

COMMENT ON COLUMN identity.devices.trusted IS
    'El party marcó este dispositivo como de confianza. '
    'Puede usarse para omitir 2FA en dispositivos de confianza (feature futura).';

COMMENT ON COLUMN identity.devices.status IS
    'ACTIVE = puede autenticar. BLOCKED = bloqueado por fraude o política.';
