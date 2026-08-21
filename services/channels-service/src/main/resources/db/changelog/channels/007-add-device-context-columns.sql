--liquibase formatted sql
--changeset channels:007-add-device-context-columns

-- Identidad del dispositivo
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS device_type         VARCHAR(30);
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS device_model        VARCHAR(200);
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS device_manufacturer VARCHAR(100);

-- Sistema operativo
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS os_version          VARCHAR(50);

-- Versión de aplicación
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS app_version         VARCHAR(50);
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS sdk_version         VARCHAR(50);

-- Red
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS network_type        VARCHAR(20);

-- HTTP / Navegador (extraídos server-side)
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS user_agent          TEXT;
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS browser             VARCHAR(50);
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS browser_version     VARCHAR(30);

-- IP (IPv6 máx 45 chars: ::ffff:255.255.255.255)
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS ip_address          VARCHAR(45);
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS ip_country          VARCHAR(2);

-- Indicadores de seguridad
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS is_rooted           BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE channels.sessions ADD COLUMN IF NOT EXISTS is_emulator         BOOLEAN NOT NULL DEFAULT FALSE;

-- Índices para investigación de fraude
CREATE INDEX IF NOT EXISTS idx_sessions_ip_address
    ON channels.sessions (ip_address) WHERE ip_address IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sessions_device_id
    ON channels.sessions (device_id) WHERE device_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sessions_rooted_emulator
    ON channels.sessions (is_rooted, is_emulator)
    WHERE is_rooted = TRUE OR is_emulator = TRUE;
