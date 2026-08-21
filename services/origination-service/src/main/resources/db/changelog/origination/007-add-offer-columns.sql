-- Phase E: offer pricing snapshot embedded in credit_applications
ALTER TABLE origination.credit_applications
    ADD COLUMN product_code          VARCHAR(50),
    ADD COLUMN product_version       INTEGER,
    ADD COLUMN product_behavior      VARCHAR(20),
    ADD COLUMN offer_amortization_type VARCHAR(20),
    ADD COLUMN offered_amount        NUMERIC(15,2),
    ADD COLUMN offered_line          NUMERIC(15,2),
    ADD COLUMN offered_term          INTEGER,
    ADD COLUMN nominal_rate          NUMERIC(8,4),
    ADD COLUMN moratorium_rate       NUMERIC(8,4),
    ADD COLUMN opening_fee_rate      NUMERIC(8,4),
    ADD COLUMN cat                   NUMERIC(8,2),
    ADD COLUMN valid_until           TIMESTAMPTZ,
    ADD COLUMN offer_presented_at    TIMESTAMPTZ,
    ADD COLUMN offer_accepted_at     TIMESTAMPTZ;
