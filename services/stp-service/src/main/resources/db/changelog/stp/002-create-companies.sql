--liquibase formatted sql

--changeset stp-service:002-create-companies
-- Una empresa con contrato propio ante STP. Lo que en el legado eran constantes en el código
-- (nombre de empresa, institución operante, prefijo de clave de rastreo) aquí son columnas.
CREATE TABLE stp.companies (
    company_id           UUID         NOT NULL,
    code                 VARCHAR(40)  NOT NULL,
    stp_empresa          VARCHAR(40)  NOT NULL,
    institucion_operante INTEGER      NOT NULL,
    tracking_prefix      VARCHAR(4)   NOT NULL,
    -- VARCHAR y no CHAR: Postgres reporta CHAR como bpchar (Types#CHAR) y ddl-auto=validate
    -- lo rechaza contra un String.
    clabe_bank_code      VARCHAR(3)   NOT NULL DEFAULT '646',
    clabe_plaza_code     VARCHAR(3)   NOT NULL DEFAULT '180',
    clabe_client_prefix  VARCHAR(6),
    status               VARCHAR(20)  NOT NULL
        CONSTRAINT companies_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED', 'RETIRED')),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT companies_pk PRIMARY KEY (company_id),
    CONSTRAINT companies_code_uq UNIQUE (code),
    CONSTRAINT companies_empresa_uq UNIQUE (stp_empresa)
);
