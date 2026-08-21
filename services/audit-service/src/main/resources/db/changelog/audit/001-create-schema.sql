--liquibase formatted sql

--changeset audit-service:001-create-schema
CREATE SCHEMA IF NOT EXISTS audit;
