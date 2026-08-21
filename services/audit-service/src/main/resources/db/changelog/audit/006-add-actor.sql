--liquibase formatted sql

--changeset audit-service:006-add-actor
-- Auditoría total con ACTOR: quién causó el evento (empleado, sistema o el propio sujeto). Antes solo
-- se sabía QUÉ pasó y sobre qué agregado, no QUIÉN lo hizo.
ALTER TABLE audit.audit_entries ADD COLUMN actor VARCHAR(120);
CREATE INDEX audit_entries_actor_idx ON audit.audit_entries (actor) WHERE actor IS NOT NULL;
