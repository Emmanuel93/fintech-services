--liquibase formatted sql
--changeset notifications:003-create-notification-templates author:system

CREATE TABLE notifications.notification_templates (
    template_id UUID        NOT NULL DEFAULT gen_random_uuid(),
    event_type  VARCHAR(30) NOT NULL,
    channel     VARCHAR(20) NOT NULL,
    locale      VARCHAR(10) NOT NULL,
    subject     VARCHAR(200),
    body        TEXT        NOT NULL,

    CONSTRAINT pk_notification_templates PRIMARY KEY (template_id)
);

CREATE UNIQUE INDEX idx_notification_templates_key
    ON notifications.notification_templates (event_type, channel, locale);
