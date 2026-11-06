--liquibase formatted sql
--changeset stp-service:016-cobro-por-clabe
--comment Collection CLABEs (one per person, never recycled) and the inbox of SPEI credits received on them.

-- A person pays by SPEI to a CLABE that is theirs alone: 646 (STP) + 180 (plaza) + the company's 4-digit client
-- prefix assigned by STP + a 7-digit consecutive + the check digit. The CLABE says WHO paid; the numeric reference
-- of the transfer says WHICH loan. A CLABE is never given to anyone else, even after it is closed: a payer that kept
-- it in their banking app would otherwise pay someone else's loan.
CREATE TABLE stp.collection_accounts (
    collection_account_id UUID         NOT NULL,
    company_id            UUID         NOT NULL,
    party_id              UUID         NOT NULL,
    clabe                 VARCHAR(18)  NOT NULL,
    holder_name           VARCHAR(120),
    tax_id                VARCHAR(18),
    status                VARCHAR(12)  NOT NULL
        CONSTRAINT collection_accounts_status_chk CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    status_reason         VARCHAR(300),
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT collection_accounts_pk PRIMARY KEY (collection_account_id),
    CONSTRAINT collection_accounts_clabe_uq UNIQUE (clabe),
    CONSTRAINT collection_accounts_party_uq UNIQUE (company_id, party_id)
);

-- The consecutive of each company's range. Transactional with the account it numbers.
CREATE TABLE stp.collection_sequences (
    company_id UUID   NOT NULL,
    last_value BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT collection_sequences_pk PRIMARY KEY (company_id)
);

-- The inbox: every credit STP announced on one of our CLABEs, as it came, with the decision taken while STP waited
-- (confirm or return) and the two dates of the money: when it arrived (the portfolio's) and the SPEI operation date
-- (the bank's). Idempotent by STP's id and by tracking key + operation date: STP retries its webhook, and the same
-- credit seen again is answered with the same decision, never processed twice.
CREATE TABLE stp.inbound_credits (
    inbound_credit_id     UUID          NOT NULL,
    company_id            UUID          NOT NULL,
    stp_id                BIGINT        NOT NULL,
    tracking_key          VARCHAR(30)   NOT NULL,
    operation_date        DATE          NOT NULL,
    received_at           TIMESTAMPTZ   NOT NULL,
    amount                NUMERIC(19,2) NOT NULL,
    beneficiary_clabe     VARCHAR(18)   NOT NULL,
    beneficiary_name      VARCHAR(120),
    payer_name            VARCHAR(120),
    payer_account         VARCHAR(20),
    payer_institution     INTEGER,
    payer_tax_id          VARCHAR(18),
    payment_type          INTEGER,
    concept               VARCHAR(210),
    numeric_reference     BIGINT,
    provider_settled_at   BIGINT,
    collection_account_id UUID,
    party_id              UUID,
    decision              VARCHAR(10)   NOT NULL
        CONSTRAINT inbound_credits_decision_chk CHECK (decision IN ('CONFIRMED', 'RETURNED')),
    return_cause          INTEGER,
    return_reason         VARCHAR(120),
    created_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT inbound_credits_pk PRIMARY KEY (inbound_credit_id),
    CONSTRAINT inbound_credits_stp_id_uq UNIQUE (company_id, stp_id),
    CONSTRAINT inbound_credits_tracking_uq UNIQUE (company_id, tracking_key, operation_date)
);

CREATE INDEX inbound_credits_party_idx ON stp.inbound_credits (party_id, received_at);
CREATE INDEX inbound_credits_operation_date_idx ON stp.inbound_credits (operation_date);

-- The local company's client prefix: the 4 digits STP assigns per company. It was seeded as '000001', which is not
-- a client prefix and would have produced 20-digit CLABEs. 3780 is the test prefix of the local stack; each
-- environment loads the one STP assigned it through the company administration.
UPDATE stp.companies SET clabe_client_prefix = '3780'
 WHERE company_id = '9b1d0000-0000-4000-8000-000000000001' AND clabe_client_prefix = '000001';
