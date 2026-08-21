--liquibase formatted sql

--changeset stp-service:008-create-tracking-key-sequences
-- No es una SEQUENCE de Postgres: hay que reiniciar por (empresa, día) y el valor tiene que ser
-- transaccional con la orden. El legado usaba el PK autoincremental de la tabla, atando el
-- identificador ante Banxico a un detalle de implementación.
CREATE TABLE stp.tracking_key_sequences (
    company_id    UUID   NOT NULL,
    business_date DATE   NOT NULL,
    last_value    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT tracking_key_sequences_pk PRIMARY KEY (company_id, business_date)
);
