--liquibase formatted sql
--changeset channels:003-create-sessions

CREATE TABLE channels.sessions (
    session_id        UUID        NOT NULL PRIMARY KEY,
    channel_id        UUID        NOT NULL REFERENCES channels.channels (channel_id),
    channel_type      VARCHAR(50) NOT NULL,
    party_id          UUID,
    device_id         VARCHAR(200),
    os                VARCHAR(100),
    is_trusted_device BOOLEAN     NOT NULL DEFAULT FALSE,
    status            VARCHAR(20) NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    closed_at         TIMESTAMPTZ
);

CREATE INDEX idx_sessions_party_channel ON channels.sessions (party_id, channel_type)
    WHERE party_id IS NOT NULL;
CREATE INDEX idx_sessions_status ON channels.sessions (status);
CREATE INDEX idx_sessions_expires_at ON channels.sessions (expires_at);
