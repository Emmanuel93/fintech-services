--liquibase formatted sql
--changeset banking:002-create-bank-accounts
--comment Nuestras cuentas. Antes vivían dentro del conector de STP.

-- La CLABE de la que sale el dinero es una decisión de TESORERÍA, no un detalle de cómo se firma
-- una cadena original. Mientras vivió en `stp.ordering_accounts`, cada proveedor nuevo habría
-- traído su propia copia del catálogo, y nadie podía saber la posición bancaria consolidada.
CREATE TABLE banking.bank_accounts (
    bank_account_id   UUID          NOT NULL DEFAULT gen_random_uuid(),
    company_id        UUID,
    institution_code  VARCHAR(10)   NOT NULL,
    institution_name  VARCHAR(120)  NOT NULL,
    clabe             VARCHAR(18)   NOT NULL,
    holder_name       VARCHAR(150)  NOT NULL,
    tax_id            VARCHAR(18),
    currency          VARCHAR(3)    NOT NULL DEFAULT 'MXN',

    -- La cuenta del catálogo contable contra la que se concilia. Sin esto, el saldo del banco y el
    -- del mayor son dos números que nadie puede cruzar.
    ledger_account    VARCHAR(10)   NOT NULL,
    -- Las dos puentes, una por dirección. Sin ellas un movimiento que el banco reporta y la
    -- plataforma no reconoce desaparece del cierre en vez de quedar declarado como partida
    -- pendiente. Son dos porque un abono sin dueño es un PASIVO y un cargo sin aclarar un ACTIVO:
    -- una sola cuenta obligaría a compensarlos entre sí (ver `accounting:011-bank-suspense`).
    suspense_credit_account VARCHAR(10) NOT NULL DEFAULT '2109',   -- abonos por identificar
    suspense_debit_account  VARCHAR(10) NOT NULL DEFAULT '1109',   -- cargos por aclarar

    -- Identificador ante el proveedor. Nulo mientras la cuenta no opere por ese rail.
    provider_client_ref VARCHAR(40),

    status            VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_bank_accounts PRIMARY KEY (bank_account_id),
    CONSTRAINT uq_bank_accounts_clabe UNIQUE (clabe),
    CONSTRAINT chk_bank_account_status CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    CONSTRAINT chk_bank_account_clabe_len CHECK (LENGTH(clabe) = 18)
);

CREATE INDEX idx_bank_accounts_company ON banking.bank_accounts (company_id) WHERE status = 'ACTIVE';
