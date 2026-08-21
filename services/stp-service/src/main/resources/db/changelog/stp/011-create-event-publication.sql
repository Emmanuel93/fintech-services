--liquibase formatted sql

--changeset stp-service:011-create-event-publication
-- Requisito de arranque: spring-modulith-starter-jpa la mapea y ddl-auto=validate falla sin ella.
CREATE TABLE IF NOT EXISTS stp.event_publication (
    id               UUID        NOT NULL,
    listener_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    serialized_event TEXT        NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ,
    CONSTRAINT event_publication_pk PRIMARY KEY (id)
);

CREATE INDEX event_publication_completion_idx
    ON stp.event_publication (completion_date)
    WHERE completion_date IS NULL;
