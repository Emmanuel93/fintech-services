--liquibase formatted sql
--changeset sales-org:009-politica-de-colocacion author:system
--comment The placement policy of the distributor line: what a distributor may place to each kind of client, per unit.

-- Only the distributor line has one: a direct credit's amount and term come from the score in its origination offer,
-- while a distributor's clients do not pass through the score, so the branch bounds them here. It lives in the tree
-- like the VAT rate: declared at a branch (or higher) and inherited by everything below; a distributor may declare her
-- own, only stricter. One row per unit and client type; a unit without rows inherits.
CREATE TABLE IF NOT EXISTS sales_org.placement_policy_rules (
    rule_id          UUID          NOT NULL,
    unit_id          UUID          NOT NULL,
    client_type      VARCHAR(20)   NOT NULL,
    max_loan_amount  NUMERIC(14,2),
    max_outstanding  NUMERIC(14,2),
    max_active_loans INTEGER,
    max_term         INTEGER,
    updated_by       VARCHAR(120),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_placement_policy_rules PRIMARY KEY (rule_id),
    CONSTRAINT uq_placement_policy_rules_unit_type UNIQUE (unit_id, client_type),
    CONSTRAINT fk_placement_policy_rules_unit FOREIGN KEY (unit_id) REFERENCES sales_org.org_units (unit_id),
    CONSTRAINT ck_placement_policy_rules_client_type CHECK (client_type IN ('NUEVO', 'REVOLVENTE')),
    CONSTRAINT ck_placement_policy_rules_positive CHECK (
        (max_loan_amount IS NULL OR max_loan_amount > 0) AND (max_outstanding IS NULL OR max_outstanding > 0)
        AND (max_active_loans IS NULL OR max_active_loans >= 1) AND (max_term IS NULL OR max_term >= 1))
);

COMMENT ON TABLE sales_org.placement_policy_rules IS
    'Política de colocación de la línea de distribuidora por unidad y tipo de cliente (NUEVO / REVOLVENTE); sin filas, la unidad hereda';
COMMENT ON COLUMN sales_org.placement_policy_rules.max_loan_amount IS 'Monto máximo de una colocación a un cliente de este tipo';
COMMENT ON COLUMN sales_org.placement_policy_rules.max_outstanding IS 'Saldo máximo del cliente con la distribuidora, sumando sus préstamos vivos y el nuevo';
COMMENT ON COLUMN sales_org.placement_policy_rules.max_active_loans IS 'Préstamos vivos a la vez, contando el nuevo';
COMMENT ON COLUMN sales_org.placement_policy_rules.max_term IS 'Plazo máximo en quincenas';
