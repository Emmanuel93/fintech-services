--liquibase formatted sql
--changeset notifications:002-create-notification-policies author:system

CREATE TABLE notifications.notification_policies (
    policy_id        UUID         NOT NULL DEFAULT gen_random_uuid(),
    event_type       VARCHAR(30)  NOT NULL,
    value_tier       VARCHAR(12)  NOT NULL,
    channel_strategy VARCHAR(20)  NOT NULL,
    primary_channel  VARCHAR(20)  NOT NULL,
    version          INT          NOT NULL,
    status           VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notification_policies PRIMARY KEY (policy_id),
    CONSTRAINT chk_notification_policies_status CHECK (status IN ('DRAFT','ACTIVE','DEPRECATED')),
    CONSTRAINT chk_notification_policies_tier CHECK (value_tier IN ('ALTO','MEDIO','COMPLIANCE','PARTNER')),
    CONSTRAINT chk_notification_policies_strategy CHECK (channel_strategy IN ('SIMULTANEOUS','SEQUENTIAL_FALLBACK'))
);

-- Una ACTIVE por eventType.
CREATE UNIQUE INDEX idx_notification_policies_active
    ON notifications.notification_policies (event_type)
    WHERE status = 'ACTIVE';

CREATE TABLE notifications.notification_policy_fallback_channels (
    policy_id UUID        NOT NULL REFERENCES notifications.notification_policies (policy_id),
    position  INT         NOT NULL,
    channel   VARCHAR(20) NOT NULL,
    CONSTRAINT pk_notification_policy_fallback_channels PRIMARY KEY (policy_id, position)
);
