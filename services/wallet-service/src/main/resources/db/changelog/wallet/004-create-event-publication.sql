--liquibase formatted sql
--changeset wallet:004-create-event-publication author:system
CREATE TABLE IF NOT EXISTS event_publication (
    id               UUID        NOT NULL,
    listener_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    serialized_event TEXT        NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ,
    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_event_publication_completion_date
    ON event_publication (completion_date);
