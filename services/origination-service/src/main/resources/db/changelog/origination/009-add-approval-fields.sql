-- D3 Origination — Phase H: manual & committee approval fields
-- Adds approvalFlow (who decides), decidedBy (actor), rejectedAt (for UW-06 cooldown).
-- Also widens the status CHECK to include COMMITTEE_REVIEW.

ALTER TABLE origination.credit_applications
    ADD COLUMN IF NOT EXISTS approval_flow  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS decided_by     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS rejected_at    TIMESTAMPTZ;

-- Update status CHECK to include COMMITTEE_REVIEW
ALTER TABLE origination.credit_applications
    DROP CONSTRAINT IF EXISTS ck_credit_app_status;

ALTER TABLE origination.credit_applications
    ADD CONSTRAINT ck_credit_app_status CHECK (status IN (
        'DRAFT','PENDING_SCORING','SCORING',
        'UNDER_MANUAL_REVIEW','COMMITTEE_REVIEW',
        'PENDING_DOCUMENTS',
        'APPROVED',
        'OFFER_PRESENTED','OFFER_ACCEPTED','OFFER_EXPIRED','OFFER_REJECTED',
        'PENDING_SIGNATURE','CONTRACT_SIGNED','DISBURSED',
        'REJECTED','FAILED','CANCELLED'));

-- Index for UW-06 cooldown lookups
CREATE INDEX IF NOT EXISTS idx_credit_app_rejected_at
    ON origination.credit_applications (prospect_id, product_type, rejected_at)
    WHERE status = 'REJECTED';

-- Update OA-03 partial unique index to also exclude COMMITTEE_REVIEW from "active" (same terminal set)
-- Note: COMMITTEE_REVIEW is non-terminal (still awaiting decision), so it stays in the active set.
-- No change needed to uq_credit_app_active_per_product.
