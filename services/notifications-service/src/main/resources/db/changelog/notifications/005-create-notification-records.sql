--liquibase formatted sql
--changeset notifications:005-create-notification-records author:system
--comment recipient_id es obligorPartyId para las notificaciones post-activación y prospectId para
--comment OFFER_PRESENTED (todavía no existe un Party correlacionable en ese punto del ciclo de vida).

CREATE TABLE notifications.notification_records (
    notification_id  UUID        NOT NULL DEFAULT gen_random_uuid(),
    source_event_id  VARCHAR(200) NOT NULL,
    recipient_id     UUID        NOT NULL,
    event_type       VARCHAR(30) NOT NULL,
    channel          VARCHAR(20) NOT NULL,
    status           VARCHAR(10) NOT NULL,
    failure_reason   VARCHAR(200),
    sent_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notification_records PRIMARY KEY (notification_id),
    CONSTRAINT chk_notification_records_status CHECK (status IN ('SENT','FAILED'))
);

-- Idempotencia: un replay de Kafka del mismo evento no duplica el envío por canal.
CREATE UNIQUE INDEX idx_notification_records_source_channel
    ON notifications.notification_records (source_event_id, channel);

CREATE INDEX idx_notification_records_recipient ON notifications.notification_records (recipient_id);
