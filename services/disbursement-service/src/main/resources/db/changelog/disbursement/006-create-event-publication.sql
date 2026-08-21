--liquibase formatted sql

--changeset disbursement-service:006-create-event-publication
-- Requisito de arranque: spring-modulith-starter-jpa la mapea y ddl-auto=validate falla sin ella.
CREATE TABLE IF NOT EXISTS disbursement.event_publication (
    id               UUID        NOT NULL,
    listener_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    serialized_event TEXT        NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ,
    CONSTRAINT event_publication_pk PRIMARY KEY (id)
);

CREATE INDEX event_publication_completion_idx
    ON disbursement.event_publication (completion_date)
    WHERE completion_date IS NULL;
