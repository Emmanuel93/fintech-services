--liquibase formatted sql

--changeset charges-service:004-create-event-publication
-- Spring Modulith event_publication table (required by spring-modulith-starter-jpa)
CREATE TABLE IF NOT EXISTS charges.event_publication (
    id              UUID        NOT NULL,
    listener_id     TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    serialized_event TEXT       NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date TIMESTAMPTZ,
    CONSTRAINT event_publication_pk PRIMARY KEY (id)
);

CREATE INDEX event_publication_completion_date_idx
    ON charges.event_publication (completion_date)
    WHERE completion_date IS NULL;
