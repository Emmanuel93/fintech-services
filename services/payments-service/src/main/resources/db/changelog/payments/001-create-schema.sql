--liquibase formatted sql

--changeset payments-service:001-create-schema
CREATE SCHEMA IF NOT EXISTS payments;
