-- D3 Origination — credit_applications (underwriting subdomain, ADR-001)
-- A CreditApplication is created when an onboarded prospect selects a product.
-- Its creation emits ScoreRequested(productType) which triggers the scoring decision.
CREATE TABLE origination.credit_applications (
    application_id      UUID         NOT NULL,
    prospect_id         UUID         NOT NULL,
    prospect_type       VARCHAR(20)  NOT NULL,
    product_type        VARCHAR(30)  NOT NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'PENDING_SCORING',
    requested_amount    NUMERIC(15,2),
    requested_term      INTEGER,

    -- Scoring linkage / decision outcome (populated in Phase C by the scoring consumer)
    score_request_id    UUID,
    risk_level          VARCHAR(10),
    decision            VARCHAR(20),
    rejection_reason    VARCHAR(500),

    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_credit_applications PRIMARY KEY (application_id),
    CONSTRAINT ck_credit_app_status CHECK (status IN (
        'DRAFT','PENDING_SCORING','SCORING','APPROVED','UNDER_MANUAL_REVIEW',
        'PENDING_DOCUMENTS','OFFER_PRESENTED','OFFER_ACCEPTED','OFFER_EXPIRED','OFFER_REJECTED',
        'PENDING_SIGNATURE','CONTRACT_SIGNED','DISBURSED','REJECTED','FAILED','CANCELLED')),
    CONSTRAINT ck_credit_app_product CHECK (product_type IN (
        'PERSONAL_LOAN','REVOLVING_LINE','PAYROLL_LOAN','GROUP_LOAN','DISTRIBUTOR_LINE')),
    CONSTRAINT ck_credit_app_prospect_type CHECK (prospect_type IN ('INDIVIDUAL','BUSINESS')),
    CONSTRAINT ck_credit_app_amount CHECK (requested_amount IS NULL OR requested_amount > 0),
    CONSTRAINT ck_credit_app_term CHECK (requested_term IS NULL OR requested_term > 0)
);

-- OA-03: only one active application per (prospect_id, product_type).
-- Active = not in a terminal/closed state.
-- OA-03: APPROVED is non-terminal (offer/contract/disbursement follow).
-- Active = not in a truly closed/terminal state.
CREATE UNIQUE INDEX uq_credit_app_active_per_product
    ON origination.credit_applications (prospect_id, product_type)
    WHERE status NOT IN ('REJECTED','FAILED','CANCELLED','OFFER_REJECTED','OFFER_EXPIRED','DISBURSED');

CREATE INDEX idx_credit_app_prospect_id ON origination.credit_applications (prospect_id);
CREATE INDEX idx_credit_app_status      ON origination.credit_applications (status);
