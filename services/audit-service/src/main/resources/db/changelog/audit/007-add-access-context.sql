--liquibase formatted sql

--changeset audit-service:007-add-access-context
-- Bitácora de ACCESO, no sólo de hechos de dominio. Antes el log sabía QUÉ le pasó a cada agregado
-- y (desde 006) QUIÉN lo causó, pero no registraba que un usuario entrara a una pantalla, buscara,
-- descargara un documento o consumiera información: el "quién consultó qué, desde dónde y cuándo".
-- Estas columnas capturan ese contexto. Todas nullable: los hechos de dominio existentes no las traen.
ALTER TABLE audit.audit_entries
    ADD COLUMN category      VARCHAR(20)  NOT NULL DEFAULT 'DOMAIN_EVENT', -- DOMAIN_EVENT | ACCESS
    ADD COLUMN action        VARCHAR(40),      -- VIEW | SEARCH | DOWNLOAD | EXPORT | READ | MUTATION | LOGIN
    ADD COLUMN actor_roles   VARCHAR(255),     -- roles vigentes del actor al momento del acceso
    ADD COLUMN actor_channel VARCHAR(40),      -- BACKOFFICE | MOBILE | ...
    ADD COLUMN actor_ip      VARCHAR(64),      -- IP del cliente (X-Forwarded-For / X-Real-IP)
    ADD COLUMN user_agent    VARCHAR(512),     -- navegador/cliente
    ADD COLUMN session_id    VARCHAR(120),     -- sesión/correlación
    ADD COLUMN resource_type VARCHAR(80),      -- sobre QUÉ: pantalla/recurso (portfolio, client, application...)
    ADD COLUMN resource_id   VARCHAR(120),     -- id concreto consultado, si aplica
    ADD COLUMN http_method   VARCHAR(10),
    ADD COLUMN http_path     VARCHAR(512),
    ADD COLUMN http_query    VARCHAR(1024),    -- query string, saneada
    ADD COLUMN outcome       VARCHAR(20),      -- SUCCESS | DENIED | ERROR
    ADD COLUMN status_code   INT,
    ADD COLUMN duration_ms   BIGINT,
    ADD COLUMN occurred_at   TIMESTAMPTZ;      -- CUÁNDO ocurrió el acceso (reloj del canal); created_at es el sello de registro

-- Trazabilidad de la bitácora de acceso: por quién, desde qué IP, qué acción y cuándo.
CREATE INDEX audit_entries_category_idx    ON audit.audit_entries (category);
CREATE INDEX audit_entries_action_idx      ON audit.audit_entries (action)      WHERE action IS NOT NULL;
CREATE INDEX audit_entries_actor_ip_idx    ON audit.audit_entries (actor_ip)    WHERE actor_ip IS NOT NULL;
CREATE INDEX audit_entries_occurred_at_idx ON audit.audit_entries (occurred_at DESC) WHERE occurred_at IS NOT NULL;
CREATE INDEX audit_entries_resource_idx    ON audit.audit_entries (resource_type, resource_id) WHERE resource_type IS NOT NULL;
