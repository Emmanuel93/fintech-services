--liquibase formatted sql
--changeset payments-service:006-abonos-y-saldo-a-favor
--comment Credits received by CLABE, how each one was applied (referenced, by rule, credit balance) and the payer's credit balance.

-- What payments needs of each loan to apply a credit without asking anyone: who owes it, how old it is, its payment
-- reference (the numeric reference of a SPEI transfer that names it) and its product.
ALTER TABLE payments.account_balance_snapshots
    ADD COLUMN activated_at      TIMESTAMPTZ,
    ADD COLUMN payment_reference BIGINT,
    ADD COLUMN product_type      VARCHAR(40);
CREATE INDEX account_balance_snapshots_obligor_idx ON payments.account_balance_snapshots (obligor_party_id);
CREATE INDEX account_balance_snapshots_reference_idx ON payments.account_balance_snapshots (payment_reference);

-- A payment order now says where its money came from and, when it is part of a received credit, which one, in which
-- order it was applied and how (REFERENCED: the transfer named the loan; BY_RULE: seniority; CREDIT_BALANCE: the
-- payer's balance in favor applied to a due installment). The two dates travel with it: received (portfolio) and
-- SPEI operation date (bank).
ALTER TABLE payments.payment_orders
    ADD COLUMN funding_source    VARCHAR(20) NOT NULL DEFAULT 'DIRECT'
        CONSTRAINT payment_orders_funding_chk CHECK (funding_source IN ('DIRECT', 'SPEI_CREDIT', 'CREDIT_BALANCE')),
    ADD COLUMN inbound_credit_id UUID,
    ADD COLUMN allocation_seq    INTEGER,
    ADD COLUMN allocation_mode   VARCHAR(20),
    ADD COLUMN received_at       TIMESTAMPTZ,
    ADD COLUMN operation_date    DATE,
    ADD COLUMN tracking_key      VARCHAR(30);
CREATE INDEX payment_orders_inbound_credit_idx ON payments.payment_orders (inbound_credit_id);

-- A credit received on a payer's CLABE, as payments applied it. One row per credit; its lines say where each peso went.
CREATE TABLE payments.received_credits (
    inbound_credit_id   UUID          NOT NULL,
    party_id            UUID          NOT NULL,
    amount              NUMERIC(19,2) NOT NULL,
    received_at         TIMESTAMPTZ   NOT NULL,
    operation_date      DATE          NOT NULL,
    tracking_key        VARCHAR(30)   NOT NULL,
    numeric_reference   BIGINT,
    reference_status    VARCHAR(12)   NOT NULL
        CONSTRAINT received_credits_reference_chk CHECK (reference_status IN ('MATCHED', 'NOT_FOUND', 'ABSENT')),
    to_loans            NUMERIC(19,2) NOT NULL,
    to_credit_balance   NUMERIC(19,2) NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT received_credits_pk PRIMARY KEY (inbound_credit_id),
    CONSTRAINT received_credits_split_chk CHECK (to_loans + to_credit_balance = amount)
);
CREATE INDEX received_credits_party_idx ON payments.received_credits (party_id, received_at);

CREATE TABLE payments.credit_allocations (
    allocation_id     UUID          NOT NULL,
    inbound_credit_id UUID          NOT NULL,
    seq               INTEGER       NOT NULL,
    mode              VARCHAR(20)   NOT NULL
        CONSTRAINT credit_allocations_mode_chk CHECK (mode IN ('REFERENCED', 'BY_RULE', 'CREDIT_BALANCE')),
    credit_account_id UUID,
    amount            NUMERIC(19,2) NOT NULL CONSTRAINT credit_allocations_amount_chk CHECK (amount > 0),
    payment_order_id  UUID,
    created_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT credit_allocations_pk PRIMARY KEY (allocation_id),
    CONSTRAINT credit_allocations_seq_uq UNIQUE (inbound_credit_id, seq),
    CONSTRAINT credit_allocations_fk FOREIGN KEY (inbound_credit_id) REFERENCES payments.received_credits (inbound_credit_id)
);

-- The payer's credit balance (saldo a favor): money already in the bank that no loan owed when it arrived. It is
-- applied to the payer's next due installment, or refunded. Every change is a movement, idempotent by its source.
CREATE TABLE payments.customer_balances (
    party_id   UUID          NOT NULL,
    balance    NUMERIC(19,2) NOT NULL DEFAULT 0 CONSTRAINT customer_balances_nonnegative_chk CHECK (balance >= 0),
    version    BIGINT        NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT customer_balances_pk PRIMARY KEY (party_id)
);

CREATE TABLE payments.customer_balance_movements (
    movement_id       UUID          NOT NULL,
    party_id          UUID          NOT NULL,
    movement_type     VARCHAR(30)   NOT NULL
        CONSTRAINT customer_balance_movements_type_chk CHECK (movement_type IN
            ('EXCESS_FROM_CREDIT', 'REJECTED_ALLOCATION', 'APPLIED_TO_LOAN', 'APPLICATION_REJECTED', 'REFUND')),
    amount            NUMERIC(19,2) NOT NULL,
    balance_after     NUMERIC(19,2) NOT NULL,
    source_key        VARCHAR(160)  NOT NULL,
    inbound_credit_id UUID,
    payment_order_id  UUID,
    credit_account_id UUID,
    business_date     DATE          NOT NULL,
    occurred_at       TIMESTAMPTZ   NOT NULL,
    note              VARCHAR(300),
    CONSTRAINT customer_balance_movements_pk PRIMARY KEY (movement_id),
    CONSTRAINT customer_balance_movements_source_uq UNIQUE (movement_type, source_key)
);
CREATE INDEX customer_balance_movements_party_idx ON payments.customer_balance_movements (party_id, occurred_at);

-- Each person's collection CLABE, as the connector assigned it.
CREATE TABLE payments.collection_accounts (
    party_id              UUID        NOT NULL,
    collection_account_id UUID        NOT NULL,
    clabe                 VARCHAR(18) NOT NULL,
    status                VARCHAR(12) NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT collection_accounts_pk PRIMARY KEY (party_id)
);
