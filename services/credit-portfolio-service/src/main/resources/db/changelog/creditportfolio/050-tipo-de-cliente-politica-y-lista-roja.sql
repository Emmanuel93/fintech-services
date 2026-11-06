--liquibase formatted sql
--changeset credit-portfolio:050-tipo-de-cliente-politica-y-lista-roja
--comment The distributor line's client side: the branch's placement policy (copied from sales-org), the client's placements by his id, and the red list.

-- The portfolio's copy of each unit's resolved placement policy (sales-org.placement-policy-changed): what a
-- distributor under that unit may place to a NUEVO and to a REVOLVENTE client. A line finds its policy by its holder
-- (her DISTRIBUTOR node carries her party_ref) and otherwise by its origin branch. Kept like the product
-- configuration: a copy fed by events, read at authorization without a call.
CREATE TABLE credit_portfolio.placement_policies (
    unit_code            VARCHAR(40)   NOT NULL,
    unit_id              UUID,
    path                 TEXT,
    level_code           VARCHAR(40),
    party_ref            UUID,
    configured           BOOLEAN       NOT NULL DEFAULT FALSE,
    inherited_from       VARCHAR(40),
    new_max_loan_amount  NUMERIC(14,2),
    new_max_outstanding  NUMERIC(14,2),
    new_max_active_loans INTEGER,
    new_max_term         INTEGER,
    rev_max_loan_amount  NUMERIC(14,2),
    rev_max_outstanding  NUMERIC(14,2),
    rev_max_active_loans INTEGER,
    rev_max_term         INTEGER,
    event_id             VARCHAR(60),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_placement_policies PRIMARY KEY (unit_code)
);
CREATE INDEX idx_placement_policies_party_ref ON credit_portfolio.placement_policies (party_ref) WHERE party_ref IS NOT NULL;
COMMENT ON TABLE credit_portfolio.placement_policies IS
    'Copia de la política de colocación de sales-org por unidad, ya resuelta la herencia; una línea la busca por su titular (party_ref) y si no por su sucursal de origen';

-- A client's placements by his id: what types him (NUEVO / REVOLVENTE) and what he owes on each line.
CREATE INDEX idx_dispositions_beneficiary ON credit_portfolio.dispositions (beneficiary_party_id)
    WHERE beneficiary_party_id IS NOT NULL;

-- The red list's stored marks: a distributor's report on a client, with a reason from the catalog and her comments.
-- The arrears marks (his voucher, his distributor's line) are derived from the plans and the lines, never stored.
-- A mark is never deleted: it is cleared, with who and why, so what was said about a client stays readable.
CREATE TABLE credit_portfolio.beneficiary_flags (
    flag_id              UUID          NOT NULL,
    beneficiary_party_id UUID          NOT NULL,
    reason               VARCHAR(30)   NOT NULL,
    source               VARCHAR(20)   NOT NULL,
    distributor_party_id UUID,
    credit_account_id    UUID,
    detail               VARCHAR(40),
    comment              VARCHAR(1000),
    raised_at            TIMESTAMPTZ   NOT NULL,
    raised_by            VARCHAR(120),
    cleared_at           TIMESTAMPTZ,
    cleared_by           VARCHAR(120),
    clear_comment        VARCHAR(1000),
    CONSTRAINT pk_beneficiary_flags PRIMARY KEY (flag_id),
    CONSTRAINT ck_beneficiary_flags_reason CHECK (reason IN ('VOUCHER_IN_ARREARS', 'DISTRIBUTOR_IN_ARREARS', 'DISTRIBUTOR_REPORT')),
    CONSTRAINT ck_beneficiary_flags_source CHECK (source IN ('SYSTEM', 'DISTRIBUTOR', 'BACKOFFICE'))
);
CREATE INDEX idx_beneficiary_flags_active ON credit_portfolio.beneficiary_flags (beneficiary_party_id) WHERE cleared_at IS NULL;
CREATE INDEX idx_beneficiary_flags_beneficiary ON credit_portfolio.beneficiary_flags (beneficiary_party_id, raised_at DESC);
COMMENT ON TABLE credit_portfolio.beneficiary_flags IS
    'Lista roja: reportes de la distribuidora sobre un cliente (motivo del catálogo y comentarios); se levantan, nunca se borran';
