--liquibase formatted sql
--changeset statement:003-candado-de-crones
--comment Candado de crones (ShedLock): el reintento de fallidos corre en una sola réplica.
CREATE TABLE IF NOT EXISTS statement.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
