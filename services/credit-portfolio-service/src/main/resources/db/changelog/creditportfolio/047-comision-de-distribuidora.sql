--liquibase formatted sql
--changeset credit-portfolio:047-comision-de-distribuidora
--comment The distributor's commission on each placement installment, netted from what she delivers; the product's commission terms and its amount and term ranges.

-- The market's rule for a distributor line: a flat share (18 %) of every installment her clients pay, deducted from
-- what she delivers against the statement if she delivers by its due date, less the later she pays (14 / 11 / 7 %),
-- nothing past the third day. The portfolio writes each installment's commission on the plan when the placement is
-- made, capped at the plan's interest so it never comes out of the capital, and nets it at the tier of the day the
-- statement gets paid (`balance_events` of type COMMISSION_NETTED, next to the cash PAYMENT_APPLIED).
ALTER TABLE credit_portfolio.installments
    ADD COLUMN IF NOT EXISTS scheduled_commission NUMERIC(19, 2);

-- The terms travel with the product version (the catalog publishes them on activation), as the billing cycle does,
-- and so do the ranges a placement is validated against: the portfolio accepted any amount and any term before.
ALTER TABLE credit_portfolio.product_config_versions
    ADD COLUMN IF NOT EXISTS distributor_commission JSONB,
    ADD COLUMN IF NOT EXISTS min_amount NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS max_amount NUMERIC(15, 2),
    ADD COLUMN IF NOT EXISTS min_term INTEGER,
    ADD COLUMN IF NOT EXISTS max_term INTEGER,
    ADD COLUMN IF NOT EXISTS term_step INTEGER;
