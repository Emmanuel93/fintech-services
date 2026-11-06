--liquibase formatted sql
--changeset accounting:022-transito-spei-y-saldos-a-favor author:system
--comment Bancos (1101) only moves with treasury's cash book, on the SPEI operation date; the rest goes through transit.

-- Two dates for each peso: the portfolio's (when the money was received or the disposition made) and the bank's (the
-- SPEI operation date: from 18:00, or on a non-business day, the next business day). A payment received at 19:00 on
-- 30 November is the portfolio's 30th and the bank's 1 December. The loan side is booked on the portfolio's date
-- against a transit account; the bank side clears it on the operation date. At a month end the transit accounts hold
-- exactly the items the bank has not reported yet, listed one by one by the close.
INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('1103', 'Remesas en camino: cobranza SPEI por acreditar', 'ASSET'),
    ('2102', 'Saldos a favor de clientes', 'LIABILITY'),
    ('2103', 'Acreedores por liquidación de operaciones: SPEI por liquidar', 'LIABILITY')
ON CONFLICT (code) DO NOTHING;

-- A disposition is booked against SPEI-to-settle; the bank clears it on the order's operation date.
UPDATE accounting.posting_rules SET credit_account = '2103'
 WHERE trigger_event IN ('DISPOSITION_SELF_USE', 'DISPOSITION_THIRD_PARTY_CREDIT', 'DISPOSITION_PAYROLL');
-- A failed or returned disposition never settled: the connector returns or cancels only an order in flight, so the
-- bank never reported it out. Reversing the disposition is what clears 2103.
UPDATE accounting.posting_rules SET debit_account = '2103'
 WHERE trigger_event IN ('DISPOSITION_RETURNED', 'DISPOSITION_FAILED');

-- The cash book keeps the portfolio's date and the tracking key of each movement.
ALTER TABLE accounting.treasury_cash_book
    ADD COLUMN portfolio_date DATE,
    ADD COLUMN tracking_key   VARCHAR(60);

-- The payers' balances in favor, movement by movement, as payments reports them: 2102 is reconciled against it.
CREATE TABLE accounting.customer_balance_movements (
    movement_id       UUID          NOT NULL,
    party_id          UUID          NOT NULL,
    movement_type     VARCHAR(30)   NOT NULL,
    amount            NUMERIC(19,2) NOT NULL,
    inbound_credit_id UUID,
    payment_order_id  UUID,
    credit_account_id UUID,
    business_date     DATE          NOT NULL,
    period            VARCHAR(6)    NOT NULL,
    received_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT customer_balance_movements_pk PRIMARY KEY (movement_id)
);
CREATE INDEX customer_balance_movements_period_idx ON accounting.customer_balance_movements (period);

-- Open items of the clearing accounts. Each voucher that moves 1103 or 2103 names the item it moves: the received
-- credit for collections in transit, the payment request for an order the bank reported out. A month end's balance
-- of those accounts is then a list of items, each one with both of its dates.
ALTER TABLE accounting.vouchers ADD COLUMN clearing_ref VARCHAR(64);
CREATE INDEX vouchers_clearing_ref_idx ON accounting.vouchers (clearing_ref) WHERE clearing_ref IS NOT NULL;

-- Which loan each outgoing order pays out, learned from disbursement's acceptance of the order. The bank reports the
-- order, the portfolio the disposition; the close pairs them through this link, whatever order the facts came in.
CREATE TABLE accounting.payout_links (
    payment_request_id UUID          NOT NULL,
    credit_account_id  UUID,
    source_reference   VARCHAR(120),
    amount             NUMERIC(19,2),
    learned_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT payout_links_pk PRIMARY KEY (payment_request_id)
);
CREATE INDEX payout_links_credit_account_idx ON accounting.payout_links (credit_account_id);
