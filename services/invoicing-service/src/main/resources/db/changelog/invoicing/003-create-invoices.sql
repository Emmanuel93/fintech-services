--liquibase formatted sql
--changeset invoicing:003-create-invoices author:system
CREATE TABLE invoicing.invoices (
    invoice_id         UUID          NOT NULL DEFAULT gen_random_uuid(),
    invoice_request_id UUID          NOT NULL,
    obligor_party_id   UUID          NOT NULL,
    period             VARCHAR(6)    NOT NULL,
    receptor_rfc       VARCHAR(13)   NOT NULL,
    receptor_name      VARCHAR(300)  NOT NULL,
    receptor_regime    VARCHAR(10),
    receptor_zip       VARCHAR(5),
    cfdi_use           VARCHAR(10),
    subtotal           NUMERIC(19,4) NOT NULL,
    iva                NUMERIC(19,4) NOT NULL,
    total              NUMERIC(19,4) NOT NULL,
    currency           VARCHAR(3)    NOT NULL DEFAULT 'MXN',
    status             VARCHAR(12)   NOT NULL DEFAULT 'DRAFT',
    folio_fiscal       UUID,
    serie              VARCHAR(10),
    folio              BIGINT,
    stamped_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_invoices PRIMARY KEY (invoice_id),
    -- idempotencia: una factura por solicitud de facturación
    CONSTRAINT uq_invoices_request UNIQUE (invoice_request_id),
    CONSTRAINT chk_invoices_status CHECK (status IN ('DRAFT','STAMPED','CANCELLED'))
);
CREATE INDEX idx_invoices_party ON invoicing.invoices (obligor_party_id);
CREATE INDEX idx_invoices_period ON invoicing.invoices (period);

CREATE TABLE invoicing.invoice_lines (
    id                UUID          NOT NULL DEFAULT gen_random_uuid(),
    invoice_id        UUID          NOT NULL,
    concept           VARCHAR(40)   NOT NULL,
    credit_account_id UUID,
    amount            NUMERIC(19,4) NOT NULL,
    is_iva            BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_invoice_lines PRIMARY KEY (id),
    CONSTRAINT fk_invoice_lines_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoicing.invoices (invoice_id) ON DELETE CASCADE
);
CREATE INDEX idx_invoice_lines_invoice ON invoicing.invoice_lines (invoice_id);
