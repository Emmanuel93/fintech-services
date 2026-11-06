--liquibase formatted sql
--changeset accounting:018-fondeo
--comment Funding: the capital paid in (equity) and the lines drawn (a financial liability at amortised cost).

-- Until now nothing booked where the lent money came from: 1101 Banks ran negative by what was disbursed (-5.8 M in
-- the demo's first month). Treasury (banking) records the capital contributions and the funding lines; accounting
-- books them:
--   contribution          1101 / 3101   (IAS 32 equity)
--   drawdown              1101 / 2201   (IFRS 9 financial liability at amortised cost)
--   opening fee of a line 2209 / 1101   (transaction cost deducted from the liability, B5.4.8; amortized to 5201)
--   interest accrued      5201 / 2202
--   interest paid         2202 / 1101
--   principal repaid      2201 / 1101
INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('3101', 'Capital social pagado', 'EQUITY'),
    ('2201', 'Préstamos bancarios y de otros organismos', 'LIABILITY'),
    ('2202', 'Intereses devengados por pagar de préstamos', 'LIABILITY'),
    ('2209', 'Costos de transacción por amortizar de préstamos', 'LIABILITY'),
    ('5201', 'Gastos por intereses de préstamos bancarios y de otros organismos', 'EXPENSE')
ON CONFLICT (code) DO NOTHING;

-- The opening fee of a funding line is amortized like a loan's fee, against interest expense.
ALTER TABLE accounting.deferred_fees DROP CONSTRAINT chk_deferred_fees_kind;
ALTER TABLE accounting.deferred_fees ADD CONSTRAINT chk_deferred_fees_kind CHECK (kind IN ('FEE', 'COST', 'FUNDING'));

-- Each funding source as treasury last reported it: what the close reconciles 2201, 2202 and 3101 against.
CREATE TABLE IF NOT EXISTS accounting.funding_balances (
    funding_source_id  UUID          NOT NULL,
    kind               VARCHAR(12)   NOT NULL,
    counterparty_name  VARCHAR(200),
    contract_reference VARCHAR(120),
    contributed        NUMERIC(19,2) NOT NULL DEFAULT 0,
    principal          NUMERIC(19,2) NOT NULL DEFAULT 0,
    interest_payable   NUMERIC(19,2) NOT NULL DEFAULT 0,
    accrued_through    DATE,
    status             VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    last_movement_id   UUID,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_funding_balances PRIMARY KEY (funding_source_id)
);
