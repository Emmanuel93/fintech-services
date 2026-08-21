--liquibase formatted sql
--changeset notifications:007-create-credit-account-progress author:system

-- Soporta #5 (cuota pagada) — aproximación local, ver CreditAccountProgress (dominio).
CREATE TABLE notifications.credit_account_progress (
    credit_account_id       UUID          NOT NULL,
    obligor_party_id        UUID          NOT NULL,
    product_type             VARCHAR(40),
    total_installments       INT,
    installments_paid_count  INT           NOT NULL DEFAULT 0,
    current_due_date         DATE,
    current_total_amount     NUMERIC(19,4),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_credit_account_progress PRIMARY KEY (credit_account_id)
);
