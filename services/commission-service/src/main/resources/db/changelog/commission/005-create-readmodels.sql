--liquibase formatted sql
--changeset commission:005-create-readmodels author:system

-- Shadow local de saldos por cuenta — para derivar el interés cobrado (delta) de balance-updated,
-- mismo patrón que accounting.account_balance_shadows.
CREATE TABLE commission.account_balance_shadows (
    credit_account_id UUID          NOT NULL,
    interest_balance   NUMERIC(19,4) NOT NULL DEFAULT 0,
    balance_version     BIGINT       NOT NULL DEFAULT -1,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_account_balance_shadows PRIMARY KEY (credit_account_id)
);

-- Atribución distribuidor/promotor <-> crédito, proyectada desde credit-account-activated.promoterCode.
CREATE TABLE commission.credit_promoter_assignments (
    credit_account_id UUID         NOT NULL,
    beneficiary_party_id UUID      NOT NULL,
    product_type        VARCHAR(40) NOT NULL,
    active               BOOLEAN    NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_credit_promoter_assignments PRIMARY KEY (credit_account_id)
);

CREATE INDEX idx_credit_promoter_assignments_beneficiary ON commission.credit_promoter_assignments (beneficiary_party_id);
