--liquibase formatted sql
--changeset origination:014-create-document-files author:origination-service
-- El archivo del documento, no sólo su referencia.
--
-- `prospect_documents` guardaba `document_ref`: un apuntador a un archivo que nunca existió en
-- ningún lado. La consola mostraba "INE_FRONT · CAPTURED" y no había nada que abrir, así que el
-- analista decidía sobre un expediente que no podía ver.
--
-- Los bytes viven en la base porque hoy no hay almacén de objetos y un expediente de crédito no
-- puede quedarse a medias esperando infraestructura. `storage_ref` queda reservada para cuando lo
-- haya: entonces el contenido se migra y esta columna apunta al objeto, sin cambiar el contrato.
--
-- Un documento vigente por tipo y prospecto: volver a subir la INE reemplaza la anterior, que es
-- lo que la palabra "reemplazar" significa para quien la vuelve a tomar con el teléfono.
CREATE TABLE origination.prospect_document_files (
    file_id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    prospect_id   UUID         NOT NULL,
    document_type VARCHAR(40)  NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    content_type  VARCHAR(120) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    content       BYTEA,
    storage_ref   VARCHAR(500),
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_prospect_document_files PRIMARY KEY (file_id),
    CONSTRAINT uq_prospect_document_files UNIQUE (prospect_id, document_type)
);

CREATE INDEX prospect_document_files_prospect_idx
    ON origination.prospect_document_files (prospect_id);
