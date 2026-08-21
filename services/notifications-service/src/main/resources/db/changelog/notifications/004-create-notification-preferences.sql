--liquibase formatted sql
--changeset notifications:004-create-notification-preferences author:system

CREATE TABLE notifications.notification_preferences (
    party_id        UUID        NOT NULL,
    push_token      VARCHAR(500),
    whatsapp_number VARCHAR(20),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notification_preferences PRIMARY KEY (party_id)
);
