-- ─────────────────────────────────────────────────────────────────────────────
-- Immutable audit of balance mutations. source_event_id is unique → idempotency
-- for at-least-once Kafka delivery from charges/payments/collections.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE credit_portfolio.balance_events
(
    balance_event_id   UUID          NOT NULL,
    credit_account_id  UUID          NOT NULL,
    source_event_id    VARCHAR(100)  NOT NULL,
    event_type         VARCHAR(40)   NOT NULL,
    delta_amount       NUMERIC(15,2) NOT NULL,
    principal_after    NUMERIC(15,2) NOT NULL,
    interest_after     NUMERIC(15,2) NOT NULL,
    penalty_after      NUMERIC(15,2) NOT NULL,
    available_after    NUMERIC(15,2),
    applied_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_balance_events PRIMARY KEY (balance_event_id),
    CONSTRAINT uq_balance_event_source UNIQUE (source_event_id),
    CONSTRAINT fk_balance_event_account
        FOREIGN KEY (credit_account_id)
        REFERENCES credit_portfolio.credit_accounts (credit_account_id)
);

CREATE INDEX idx_balance_events_account ON credit_portfolio.balance_events (credit_account_id);
