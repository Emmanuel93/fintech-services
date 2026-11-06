--liquibase formatted sql
--changeset receipt:003-candado-de-crones author:system
--comment ShedLock: the retry of failed receipts runs on one replica.
CREATE TABLE IF NOT EXISTS receipt.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
