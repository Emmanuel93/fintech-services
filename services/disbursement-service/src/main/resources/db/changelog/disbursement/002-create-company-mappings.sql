--liquibase formatted sql

--changeset disbursement-service:002-create-company-mappings
-- Traduce la clave de empresa del emisor al companyId de este servicio. Existe para que no haya
-- que meter un switch con nombres de clientes en el código — el acoplamiento del legado que se
-- está eliminando.
CREATE TABLE disbursement.company_mappings (
    company_mapping_id UUID        NOT NULL,
    source_system      VARCHAR(60) NOT NULL,
    source_key         VARCHAR(120) NOT NULL,
    company_id         UUID        NOT NULL,
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT company_mappings_pk PRIMARY KEY (company_mapping_id),
    CONSTRAINT company_mappings_uq UNIQUE (source_system, source_key)
);

CREATE INDEX company_mappings_company_idx ON disbursement.company_mappings (company_id);
