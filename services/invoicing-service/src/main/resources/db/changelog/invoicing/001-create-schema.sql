--liquibase formatted sql
--changeset invoicing:001-create-schema author:system
CREATE SCHEMA IF NOT EXISTS invoicing;
