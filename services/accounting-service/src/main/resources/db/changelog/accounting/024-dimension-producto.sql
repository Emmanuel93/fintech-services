--liquibase formatted sql
--changeset accounting:024-dimension-producto author:system
--comment The product dimension: each loan's product learned from its activation and stamped on every voucher and entry; the close keeps the trial balance by product and branch.

-- The product of a loan, as its activation published it (credit-portfolio's productType). Learned once and never
-- rewritten, like the branch: from then on every voucher of the loan carries it.
ALTER TABLE accounting.account_balance_shadows ADD COLUMN IF NOT EXISTS product_type VARCHAR(40);

-- Every voucher and entry carries the product of its fact: the loan's; for a voucher with no loan, the item's it moves
-- (a commission lot, the bank side of an order or of a received credit); none (NULL, shown as «Institución») for what
-- belongs to the institution as a whole (its funding and its interest). Copied on the entry as the branch is, so the
-- trial balance by product aggregates entries without joining their vouchers.
ALTER TABLE accounting.vouchers ADD COLUMN IF NOT EXISTS product_type VARCHAR(40);
ALTER TABLE accounting.journal_entries ADD COLUMN IF NOT EXISTS product_type VARCHAR(40);
CREATE INDEX IF NOT EXISTS idx_vouchers_period_product ON accounting.vouchers (period, product_type);
CREATE INDEX IF NOT EXISTS idx_journal_entries_product_per ON accounting.journal_entries (period, product_type);

-- What accounting's own records already say: a commission names its product in its item (COM:<beneficiary>:<product>),
-- a lot through the facts commission reported. Loans booked before this change get theirs when credit-portfolio
-- republishes their activation or through the attribution endpoint, as the branch did.
UPDATE accounting.vouchers v
   SET product_type = split_part(v.clearing_ref, ':', 3)
 WHERE v.product_type IS NULL AND v.clearing_ref LIKE 'COM:%:%' AND split_part(v.clearing_ref, ':', 3) <> '';
UPDATE accounting.vouchers v
   SET product_type = f.product_type
  FROM (SELECT DISTINCT ON (batch_id) batch_id, product_type
          FROM accounting.commission_facts
         WHERE batch_id IS NOT NULL AND product_type IS NOT NULL
         ORDER BY batch_id, received_at) f
 WHERE v.product_type IS NULL AND v.clearing_ref = 'LOTE:' || f.batch_id;
UPDATE accounting.journal_entries e
   SET product_type = v.product_type
  FROM accounting.vouchers v
 WHERE e.voucher_id = v.voucher_id AND e.product_type IS NULL AND v.product_type IS NOT NULL;

-- The close keeps, next to the whole trial balance it published, the one by product and branch: a dimension learned
-- later fills only open months, and this is what a closed month showed when it closed.
ALTER TABLE accounting.period_closes ADD COLUMN IF NOT EXISTS trial_balance_by_product JSONB;
