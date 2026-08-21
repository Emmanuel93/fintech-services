--liquibase formatted sql

--changeset stp-service:010-create-stub-orders
-- La usa SÓLO el stub de ambientes bajos (fintech.stp.gateway.mode=stub). Queda vacía en
-- producción, donde el stub además se niega a arrancar.
CREATE TABLE stp.stub_orders (
    stub_order_id            UUID          NOT NULL,
    clave_rastreo            VARCHAR(30)   NOT NULL,
    empresa                  VARCHAR(40)   NOT NULL,
    business_date            DATE          NOT NULL,
    monto                    NUMERIC(19,2) NOT NULL,
    cuenta_ordenante         VARCHAR(20),
    nombre_ordenante         VARCHAR(150),
    rfc_curp_ordenante       VARCHAR(18),
    cuenta_beneficiario      VARCHAR(20),
    nombre_beneficiario      VARCHAR(150),
    rfc_curp_beneficiario    VARCHAR(18),
    institucion_contraparte  INTEGER,
    institucion_operante     INTEGER,
    concepto_pago            VARCHAR(40),
    referencia_numerica      BIGINT,
    tipo_pago                INTEGER,
    tipo_cuenta_beneficiario INTEGER,
    tipo_cuenta_ordenante    INTEGER,
    firma_recibida           TEXT,
    firma_valida             BOOLEAN,
    scenario                 VARCHAR(40)   NOT NULL,
    stp_response_id          BIGINT        NOT NULL,
    received_at              TIMESTAMPTZ   NOT NULL,
    CONSTRAINT stub_orders_pk PRIMARY KEY (stub_order_id),
    CONSTRAINT stub_orders_tracking_uq UNIQUE (clave_rastreo)
);

CREATE INDEX stub_orders_lookup_idx ON stp.stub_orders (empresa, business_date, received_at);
