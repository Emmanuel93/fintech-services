--liquibase formatted sql
--changeset accounting:013-candado-de-crones
--comment Candado de crones (ShedLock): una sola réplica corre cada job.

-- Con dos réplicas, cada pod disparaba su propio @Scheduled. La fila de esta tabla es el candado:
-- el pod que la toma corre el job y los demás lo saltan. Vive en el esquema del servicio, como el
-- resto de sus datos, y el vencimiento lo decide el reloj de PostgreSQL (usingDbTime).
CREATE TABLE IF NOT EXISTS accounting.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
