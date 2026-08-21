--liquibase formatted sql
--changeset accounting:004-create-readmodels author:system

-- Shadow local de saldos por cuenta — para derivar el MONTO (delta) de cada balance-updated
-- (que solo trae saldos nuevos) y para la conciliación de cartera (GL-04).
CREATE TABLE accounting.account_balance_shadows (
    credit_account_id UUID          NOT NULL,
    obligor_party_id  UUID          NOT NULL,
    principal_balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    interest_balance  NUMERIC(19,4) NOT NULL DEFAULT 0,
    penalty_balance   NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_debt        NUMERIC(19,4) NOT NULL DEFAULT 0,
    balance_version   BIGINT        NOT NULL DEFAULT -1,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_account_balance_shadows PRIMARY KEY (credit_account_id)
);

-- GL-09: delta de provisión — cuánto se ha asentado ya por cuenta (para asentar solo el cambio).
CREATE TABLE accounting.provision_ledger (
    credit_account_id    UUID          NOT NULL,
    last_booked_provision NUMERIC(19,4) NOT NULL DEFAULT 0,
    last_booked_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_provision_ledger PRIMARY KEY (credit_account_id)
);

-- Ingresos acumulados pendientes de facturar — consolidados por party/período por el BillingRunJob.
CREATE TABLE accounting.invoiceable_items (
    item_id           UUID          NOT NULL DEFAULT gen_random_uuid(),
    source_event_id   VARCHAR(120)  NOT NULL UNIQUE,
    obligor_party_id  UUID          NOT NULL,
    credit_account_id UUID          NOT NULL,
    concept           VARCHAR(40)   NOT NULL,   -- ORDINARY_INTEREST, MORATORIUM_INTEREST, *_FEE, IVA
    amount            NUMERIC(19,4) NOT NULL,
    is_iva            BOOLEAN       NOT NULL DEFAULT FALSE,
    period            VARCHAR(6)    NOT NULL,   -- YYYYMM del devengamiento
    status            VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    invoice_ref       UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_invoiceable_items PRIMARY KEY (item_id),
    CONSTRAINT chk_invoiceable_items_status CHECK (status IN ('PENDING','BILLED'))
);

CREATE INDEX idx_invoiceable_items_party_period ON accounting.invoiceable_items (obligor_party_id, period, status);
