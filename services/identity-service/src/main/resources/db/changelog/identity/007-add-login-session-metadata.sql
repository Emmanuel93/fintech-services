--liquibase formatted sql

--changeset identity:007-add-login-session-metadata author:system
--comment Agrega metadata de sesión a credenciales: última IP y timestamp de login exitoso

ALTER TABLE identity.credentials
    ADD COLUMN IF NOT EXISTS last_login_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS last_login_ip  VARCHAR(45);

COMMENT ON COLUMN identity.credentials.last_login_at
    IS 'Timestamp del último inicio de sesión exitoso (UTC). Null si nunca ha iniciado sesión.';

COMMENT ON COLUMN identity.credentials.last_login_ip
    IS 'IP del cliente en el último inicio de sesión exitoso. Soporta IPv4 (15 chars) e IPv6 (45 chars).';
