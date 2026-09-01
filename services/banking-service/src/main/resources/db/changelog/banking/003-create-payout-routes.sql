--liquibase formatted sql
--changeset banking:003-create-payout-routes
--comment De qué cuenta sale, por qué rail y con qué proveedor. Cambiar una ruta es un INSERT.

-- Sustituye a `disbursement.routing_rules`, que sólo decidía el PROVEEDOR por (empresa, rail,
-- monto) y nunca la cuenta. La cuenta la elegía el conector con un `is_default` por empresa.
CREATE TABLE banking.payout_routes (
    payout_route_id  UUID          NOT NULL DEFAULT gen_random_uuid(),
    company_id       UUID,                       -- NULL = regla por defecto de todas las empresas
    rail             VARCHAR(20)   NOT NULL,
    provider         VARCHAR(20)   NOT NULL,
    bank_account_id  UUID          NOT NULL,     -- LA CUENTA. Es lo que routing_rules no tenía
    min_amount       NUMERIC(19,2) NOT NULL DEFAULT 0,
    max_amount       NUMERIC(19,2),
    priority         INTEGER       NOT NULL DEFAULT 100,
    enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_payout_routes PRIMARY KEY (payout_route_id),
    CONSTRAINT chk_payout_rail CHECK (rail IN ('SPEI','CODI','INTERNAL')),
    CONSTRAINT chk_payout_min CHECK (min_amount >= 0),
    CONSTRAINT chk_payout_max CHECK (max_amount IS NULL OR max_amount >= min_amount),
    CONSTRAINT fk_payout_routes_account FOREIGN KEY (bank_account_id)
        REFERENCES banking.bank_accounts (bank_account_id)
);

-- El índice de la resolución: se busca por rail y se ordena por prioridad.
CREATE INDEX idx_payout_routes_lookup ON banking.payout_routes (rail, priority)
    WHERE enabled = TRUE;
