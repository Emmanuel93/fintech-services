--liquibase formatted sql
--changeset notifications:001-create-schema author:system
CREATE SCHEMA IF NOT EXISTS notifications;
