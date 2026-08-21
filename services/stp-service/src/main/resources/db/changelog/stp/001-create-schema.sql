--liquibase formatted sql

--changeset stp-service:001-create-schema
CREATE SCHEMA IF NOT EXISTS stp;
