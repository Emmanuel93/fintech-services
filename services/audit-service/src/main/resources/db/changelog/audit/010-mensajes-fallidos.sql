--liquibase formatted sql

--changeset audit-service:010-mensajes-fallidos
-- The failure registry: every message a service could not handle after its retries, from every `<topic>.DLT`.
--
-- Until now a failed message was one log line in the service that dropped it. Here it is kept with what is needed
-- to answer the three questions of an incident: which flow it broke (correlation id), where and why it failed
-- (service, consumer group, original topic/partition/offset, exception), and what to do with it (replay it to its
-- original topic once the cause is fixed, or discard it with a note). Consumers are idempotent by the fact's own
-- key, so a replay never applies anything twice.
CREATE TABLE IF NOT EXISTS audit.failed_messages (
    failed_message_id   UUID          NOT NULL,
    dlt_topic           VARCHAR(255)  NOT NULL,
    dlt_partition       INT           NOT NULL,
    dlt_offset          BIGINT        NOT NULL,
    original_topic      VARCHAR(255)  NOT NULL,
    original_partition  INT,
    original_offset     BIGINT,
    consumer_group      VARCHAR(255),
    service             VARCHAR(120),
    correlation_id      VARCHAR(100),
    message_key         TEXT,
    payload             TEXT,
    headers             JSONB         NOT NULL DEFAULT '{}'::jsonb,
    exception_class     VARCHAR(500),
    exception_message   TEXT,
    failed_at           TIMESTAMPTZ,
    received_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    status              VARCHAR(12)   NOT NULL DEFAULT 'PENDING',
    replays             INT           NOT NULL DEFAULT 0,
    resolved_by         VARCHAR(120),
    resolved_at         TIMESTAMPTZ,
    resolution_note     TEXT,
    CONSTRAINT pk_failed_messages PRIMARY KEY (failed_message_id),
    CONSTRAINT uq_failed_messages_dlt UNIQUE (dlt_topic, dlt_partition, dlt_offset),
    CONSTRAINT chk_failed_messages_status CHECK (status IN ('PENDING', 'REPLAYED', 'DISCARDED'))
);

CREATE INDEX IF NOT EXISTS idx_failed_messages_correlation ON audit.failed_messages (correlation_id);
CREATE INDEX IF NOT EXISTS idx_failed_messages_status ON audit.failed_messages (status, received_at);
CREATE INDEX IF NOT EXISTS idx_failed_messages_topic ON audit.failed_messages (original_topic, received_at);
