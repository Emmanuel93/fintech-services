--liquibase formatted sql
--changeset channels:002-create-channels

CREATE TABLE channels.channels (
    channel_id          UUID        NOT NULL PRIMARY KEY,
    channel_type        VARCHAR(50) NOT NULL UNIQUE,
    status              VARCHAR(20) NOT NULL,
    allowed_intents     TEXT        NOT NULL,
    session_ttl_minutes INT         NOT NULL DEFAULT 30,
    max_idle_minutes    INT         NOT NULL DEFAULT 10,
    rate_limit_per_hour INT         NOT NULL DEFAULT 100,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_channels_status ON channels.channels (status);
