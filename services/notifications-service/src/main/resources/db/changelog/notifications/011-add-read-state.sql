--liquibase formatted sql
--changeset notifications:011-add-read-state author:system
--comment estado de lectura del inbox in-app (fa_notifications). NULL = no leída.

ALTER TABLE notifications.notification_records
    ADD COLUMN read_at TIMESTAMPTZ;
