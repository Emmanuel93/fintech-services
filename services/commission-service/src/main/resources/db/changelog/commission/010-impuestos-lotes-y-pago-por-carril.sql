--liquibase formatted sql
--changeset commission:010-impuestos-lotes-y-pago-por-carril
--comment Commission taxes, the exact reversal of a returned payment, lots per beneficiary and product with carry-over, and the payout by the SPEI rail.

-- A commission names the payment that produced it (the payment order, the same id the portfolio's return carries) and
-- its product. A returned payment appends a reversal record (negative, `reverses_commission_id`) instead of editing the
-- original: if the original was already paid, the reversal is netted from the next lot of the same beneficiary and
-- product. The IVA the beneficiary will invoice on top of the commission is computed per record.
ALTER TABLE commission.commission_records ADD COLUMN product_type VARCHAR(40);
ALTER TABLE commission.commission_records ADD COLUMN payment_ref VARCHAR(120);
ALTER TABLE commission.commission_records ADD COLUMN iva_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE commission.commission_records ADD COLUMN reverses_commission_id UUID;
-- The portfolio's day of the payment (or of its return): the commission's period and its voucher's date.
ALTER TABLE commission.commission_records ADD COLUMN value_date DATE;
CREATE INDEX idx_commission_records_payment ON commission.commission_records (payment_ref) WHERE payment_ref IS NOT NULL;
CREATE INDEX idx_commission_records_reverses ON commission.commission_records (reverses_commission_id)
    WHERE reverses_commission_id IS NOT NULL;
CREATE INDEX idx_commission_records_open ON commission.commission_records (beneficiary_party_id, product_type, period)
    WHERE status = 'ACCRUED';

-- A lot pays one beneficiary for one product everything accrued up to its period that earlier runs left below the
-- minimum: the carry-over. It carries its taxes and the net the SPEI order pays.
ALTER TABLE commission.liquidation_batches ADD COLUMN product_type VARCHAR(40);
ALTER TABLE commission.liquidation_batches ADD COLUMN subtotal NUMERIC(19, 2);
ALTER TABLE commission.liquidation_batches ADD COLUMN iva_amount NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE commission.liquidation_batches ADD COLUMN isr_withheld NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE commission.liquidation_batches ADD COLUMN iva_withheld NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE commission.liquidation_batches ADD COLUMN net_amount NUMERIC(19, 2);
ALTER TABLE commission.liquidation_batches ADD COLUMN records_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE commission.liquidation_batches ADD COLUMN failure_reason VARCHAR(200);
ALTER TABLE commission.liquidation_batches ADD COLUMN payout_attempts INTEGER NOT NULL DEFAULT 0;
-- PENDING: opened, its SPEI not requested yet (no payout account, or the last order failed: failure_reason says why);
-- SENT: the order was requested; CONFIRMED: the bank settled it.

-- One lot per beneficiary, product and period: two runs of the same period (two replicas, a manual retry) do not pay
-- the same accrual twice.
DROP INDEX IF EXISTS commission.uq_liquidation_beneficiary_period;
CREATE UNIQUE INDEX uq_liquidation_beneficiary_product_period
    ON commission.liquidation_batches (beneficiary_party_id, coalesce(product_type, ''), period);

-- Who the commission is paid to, as commission needs it: the fiscal profile party publishes (the RFC decides whether
-- the beneficiary is a natural person, which commission withholds ISR and two thirds of the IVA from; the regime
-- decides the ISR rate) and the account the lot's SPEI goes to.
CREATE TABLE commission.beneficiary_profiles (
    party_id        UUID         NOT NULL,
    rfc             VARCHAR(13),
    party_type      VARCHAR(20),
    tax_name        VARCHAR(300),
    tax_regime      VARCHAR(5),
    payout_clabe    VARCHAR(18),
    payout_holder   VARCHAR(120),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_beneficiary_profiles PRIMARY KEY (party_id)
);
