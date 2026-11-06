--liquibase formatted sql
--changeset banking:009-fondeo
--comment Funding: where the money the institution lends comes from — its capital and its funding lines.

-- A SOFOM ENR may not take deposits from the public (LIC arts. 2 and 103; LGOAAC 87-B): it lends its own capital and
-- what financial institutions or other entities lend it under a private contract. Those are the two kinds of source.
-- Each one names its counterparty with its RFC and the document behind it (the shareholders' meeting minutes for a
-- capital contribution, the credit agreement for a line): the origin of the resources is identified (PLD, LGOAAC
-- 95 Bis), and the books carry it (IAS 32 equity, IFRS 9 financial liability at amortised cost).
CREATE TABLE banking.funding_sources (
    funding_source_id    UUID          NOT NULL DEFAULT gen_random_uuid(),
    kind                 VARCHAR(12)   NOT NULL,
    counterparty_type    VARCHAR(20)   NOT NULL,
    counterparty_name    VARCHAR(200)  NOT NULL,
    counterparty_rfc     VARCHAR(13)   NOT NULL,
    contract_reference   VARCHAR(120)  NOT NULL,
    bank_account_id      UUID          NOT NULL,
    authorized_amount    NUMERIC(19,2) NOT NULL,
    annual_rate          NUMERIC(9,6),
    opening_fee          NUMERIC(19,2) NOT NULL DEFAULT 0,
    start_date           DATE          NOT NULL,
    maturity_date        DATE,
    status               VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    contributed          NUMERIC(19,2) NOT NULL DEFAULT 0,
    principal            NUMERIC(19,2) NOT NULL DEFAULT 0,
    interest_payable     NUMERIC(19,2) NOT NULL DEFAULT 0,
    accrued_through      DATE,
    opening_fee_paid     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_by           VARCHAR(120),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version              BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_funding_sources PRIMARY KEY (funding_source_id),
    CONSTRAINT fk_funding_sources_account FOREIGN KEY (bank_account_id) REFERENCES banking.bank_accounts (bank_account_id),
    CONSTRAINT chk_funding_sources_kind CHECK (kind IN ('CAPITAL', 'CREDIT_LINE')),
    CONSTRAINT chk_funding_sources_counterparty CHECK (counterparty_type IN
        ('SHAREHOLDER', 'BANK', 'DEVELOPMENT_BANK', 'OTHER_ENTITY')),
    CONSTRAINT chk_funding_sources_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT chk_funding_sources_amounts CHECK (authorized_amount > 0 AND contributed >= 0 AND principal >= 0
        AND interest_payable >= 0 AND opening_fee >= 0),
    -- A line has a rate and a maturity; capital has neither.
    CONSTRAINT chk_funding_sources_line CHECK (kind <> 'CREDIT_LINE' OR (annual_rate IS NOT NULL AND annual_rate >= 0
        AND maturity_date IS NOT NULL AND maturity_date > start_date)),
    CONSTRAINT uq_funding_sources_contract UNIQUE (kind, counterparty_rfc, contract_reference)
);

-- Every movement of a source, with the balances it left: what accounting books and what the bank must show.
CREATE TABLE banking.funding_movements (
    funding_movement_id   UUID          NOT NULL DEFAULT gen_random_uuid(),
    funding_source_id     UUID          NOT NULL,
    movement_type         VARCHAR(20)   NOT NULL,
    amount                NUMERIC(19,2) NOT NULL,
    value_date            DATE          NOT NULL,
    accrued_from          DATE,
    accrued_through       DATE,
    reference             VARCHAR(120)  NOT NULL,
    contributed_after     NUMERIC(19,2) NOT NULL,
    principal_after       NUMERIC(19,2) NOT NULL,
    interest_payable_after NUMERIC(19,2) NOT NULL,
    recorded_by           VARCHAR(120),
    recorded_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_funding_movements PRIMARY KEY (funding_movement_id),
    CONSTRAINT fk_funding_movements_source FOREIGN KEY (funding_source_id) REFERENCES banking.funding_sources (funding_source_id),
    CONSTRAINT chk_funding_movements_type CHECK (movement_type IN ('CONTRIBUTION', 'DRAWDOWN', 'OPENING_FEE',
        'INTEREST_ACCRUAL', 'INTEREST_PAYMENT', 'PRINCIPAL_REPAYMENT')),
    CONSTRAINT chk_funding_movements_amount CHECK (amount > 0),
    -- The same instruction recorded twice (a retried request) is one movement.
    CONSTRAINT uq_funding_movements_reference UNIQUE (funding_source_id, movement_type, reference)
);

CREATE INDEX idx_funding_movements_source ON banking.funding_movements (funding_source_id, value_date);

-- The bank's side of a funding movement is matched like any other: the projection gets its own type.
ALTER TABLE banking.internal_movements DROP CONSTRAINT chk_internal_movement_type;
ALTER TABLE banking.internal_movements ADD CONSTRAINT chk_internal_movement_type CHECK (movement_type IN
    ('PAYMENT_ORDER', 'DISBURSEMENT_ORDER', 'STP_ORDER', 'JOURNAL_ENTRY', 'MANUAL_ADJUSTMENT', 'FUNDING'));
