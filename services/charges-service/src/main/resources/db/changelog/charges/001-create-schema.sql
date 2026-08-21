--liquibase formatted sql

--changeset charges-service:001-create-schema
CREATE SCHEMA IF NOT EXISTS charges;
