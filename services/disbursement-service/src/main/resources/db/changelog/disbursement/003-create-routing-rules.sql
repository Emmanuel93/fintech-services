--liquibase formatted sql

--changeset disbursement-service:003-create-routing-rules
-- (empresa, rail, rango de monto) -> proveedor. Cambiar de proveedor es un INSERT, no un despliegue.
-- company_id NULL = regla por defecto para todas las empresas.
CREATE TABLE disbursement.routing_rules (
    routing_rule_id UUID          NOT NULL,
    company_id      UUID,
    rail            VARCHAR(20)   NOT NULL
        CONSTRAINT routing_rules_rail_chk CHECK (rail IN ('SPEI', 'CODI', 'INTERNAL')),
    provider        VARCHAR(20)   NOT NULL,
    min_amount      NUMERIC(19,2) NOT NULL DEFAULT 0
        CONSTRAINT routing_rules_min_chk CHECK (min_amount >= 0),
    max_amount      NUMERIC(19,2)
        CONSTRAINT routing_rules_max_chk CHECK (max_amount IS NULL OR max_amount >= min_amount),
    priority        INTEGER       NOT NULL DEFAULT 100,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT routing_rules_pk PRIMARY KEY (routing_rule_id)
);

-- Es exactamente lo que lee RoutingService en cada despacho.
CREATE INDEX routing_rules_lookup_idx
    ON disbursement.routing_rules (rail, priority)
    WHERE enabled = TRUE;
