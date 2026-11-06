--liquibase formatted sql
--changeset accounting:020-libro-de-caja
--comment Treasury's cash book, as accounting receives it: the record 1101 is reconciled against at every close.

-- Treasury (banking) keeps every movement of the account from its own facts (STP orders settled, funding, clients'
-- payments, returns and rejections, commissions paid). Accounting books 1101 from the portfolio's and the other
-- services' facts. Two records of the same money: the month closes only if, by business day, they say the same.
CREATE TABLE accounting.treasury_cash_book (
    movement_id     UUID           PRIMARY KEY,
    movement_type   VARCHAR(30)    NOT NULL,
    direction       VARCHAR(6)     NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
    amount          NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    business_date   DATE           NOT NULL,
    period          VARCHAR(6)     NOT NULL,
    reference       VARCHAR(120),
    source_event_id VARCHAR(160)   NOT NULL,
    received_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_treasury_cash_book_period ON accounting.treasury_cash_book (period, movement_type);
