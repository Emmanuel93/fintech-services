--liquibase formatted sql

--changeset payments-service:004-create-event-publication
CREATE TABLE IF NOT EXISTS payments.event_publication (
    id                  UUID        NOT NULL,
    listener_id         TEXT        NOT NULL,
    event_type          TEXT        NOT NULL,
    serialized_event    TEXT        NOT NULL,
    publication_date    TIMESTAMPTZ NOT NULL,
    completion_date     TIMESTAMPTZ,
    CONSTRAINT event_publication_pk PRIMARY KEY (id)
);

CREATE INDEX event_publication_completion_idx
    ON payments.event_publication (completion_date)
    WHERE completion_date IS NULL;
