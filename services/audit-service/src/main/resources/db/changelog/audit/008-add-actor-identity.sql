--liquibase formatted sql

--changeset audit-service:008-add-actor-identity
-- El "quién" legible. El actor era el staffUserId (un UUID): útil para correlacionar, ilegible para
-- un auditor. Se congela junto a él el correo y el nombre con que el empleado entró al backoffice,
-- resueltos por el canal al momento del acceso (snapshot: si el correo cambia luego, el registro
-- conserva el de entonces).
ALTER TABLE audit.audit_entries
    ADD COLUMN actor_email VARCHAR(255),
    ADD COLUMN actor_name  VARCHAR(255);

CREATE INDEX audit_entries_actor_email_idx ON audit.audit_entries (actor_email) WHERE actor_email IS NOT NULL;
