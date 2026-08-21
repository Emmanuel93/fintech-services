--liquibase formatted sql
--changeset wallet:001-create-schema author:system
CREATE SCHEMA IF NOT EXISTS wallet;
