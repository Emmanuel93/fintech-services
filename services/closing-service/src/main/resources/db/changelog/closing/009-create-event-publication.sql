--liquibase formatted sql
--changeset closing:009-create-event-publication
--comment Requerida por spring-modulith-starter-jpa cuando está en el classpath.
CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID        NOT NULL,
    listener_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    serialized_event TEXT        NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ,
    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_event_pub_completion
    ON event_publication (completion_date) WHERE completion_date IS NULL;
