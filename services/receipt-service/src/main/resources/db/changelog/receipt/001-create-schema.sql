--liquibase formatted sql
--changeset receipt:001-create-schema author:system
CREATE SCHEMA IF NOT EXISTS receipt;
