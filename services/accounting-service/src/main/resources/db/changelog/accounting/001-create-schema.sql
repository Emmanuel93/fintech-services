--liquibase formatted sql
--changeset accounting:001-create-schema author:system

CREATE SCHEMA IF NOT EXISTS accounting;
