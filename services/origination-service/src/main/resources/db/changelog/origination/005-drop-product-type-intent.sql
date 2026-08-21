-- D3 Origination — ADR-001 re-orientation: persona ≠ crédito
-- The Prospect no longer carries a product intent. The product is chosen later
-- in a CreditApplication. Drop the column from the prospect onboarding aggregate.
ALTER TABLE origination.prospects DROP COLUMN IF EXISTS product_type_intent;
