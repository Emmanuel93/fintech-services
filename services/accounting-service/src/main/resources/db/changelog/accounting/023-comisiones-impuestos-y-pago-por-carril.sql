--liquibase formatted sql
--changeset accounting:023-comisiones-impuestos-y-pago-por-carril author:system
--comment Commissions with their taxes, lots paid by the SPEI rail, and 2120 reconciled item by item.

-- The beneficiary invoices IVA on the commission: it is creditable once paid (cash basis), pending until then. What is
-- withheld from a natural person (ISR, two thirds of the IVA) is owed to the SAT until it is paid on their behalf.
INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('1140', 'IVA acreditable pagado', 'ASSET'),
    ('1141', 'IVA pendiente de acreditar', 'ASSET'),
    ('2111', 'ISR retenido por enterar', 'LIABILITY'),
    ('2112', 'IVA retenido por enterar', 'LIABILITY')
ON CONFLICT (code) DO NOTHING;

-- What an outgoing order pays: a loan (credit_account_id) or another item, such as a lot of commissions (LOTE:<id>).
-- The bank side of the order (TREASURY_STP_ORDER) is paired with it at the close.
ALTER TABLE accounting.payout_links ADD COLUMN item_ref VARCHAR(64);
CREATE INDEX payout_links_item_ref_idx ON accounting.payout_links (item_ref) WHERE item_ref IS NOT NULL;

-- Commission's facts, as commission reported them, signed as they move what is owed to each beneficiary for each
-- product (2120): an accrual adds its gross (commission and IVA), a reversal takes it back, a lot's SPEI requested
-- takes the lot's gross to the SPEI in flight, and a failed one brings it back. 2120 is reconciled against them, by
-- beneficiary and product.
CREATE TABLE accounting.commission_facts (
    fact_id              VARCHAR(80)   NOT NULL,
    kind                 VARCHAR(12)   NOT NULL,
    beneficiary_party_id UUID          NOT NULL,
    product_type         VARCHAR(40),
    batch_id             UUID,
    attempt              INTEGER,
    gross                NUMERIC(19,2) NOT NULL,
    business_date        DATE          NOT NULL,
    period               VARCHAR(6)    NOT NULL,
    received_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT commission_facts_pk PRIMARY KEY (fact_id),
    CONSTRAINT commission_facts_kind_chk CHECK (kind IN ('ACCRUED', 'REVERSED', 'LOT_SENT', 'LOT_FAILED'))
);
CREATE INDEX commission_facts_key_idx ON accounting.commission_facts (beneficiary_party_id, product_type, period);
CREATE INDEX commission_facts_batch_idx ON accounting.commission_facts (batch_id) WHERE batch_id IS NOT NULL;
