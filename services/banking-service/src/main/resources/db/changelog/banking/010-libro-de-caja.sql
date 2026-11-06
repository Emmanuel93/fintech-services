--liquibase formatted sql
--changeset banking:010-libro-de-caja
--comment The treasury's cash book holds every movement of money: client payments, returns and commission payouts too.

-- The projection only had what went out through STP (disbursements) and the funding: the clients' payments (6.3 M in
-- the 3 Nov demo) and the commissions paid to distributors were missing, so it could not be reconciled with 1101.
ALTER TABLE banking.internal_movements DROP CONSTRAINT chk_internal_movement_type;
ALTER TABLE banking.internal_movements ADD CONSTRAINT chk_internal_movement_type CHECK (movement_type IN
    ('PAYMENT_ORDER', 'DISBURSEMENT_ORDER', 'STP_ORDER', 'JOURNAL_ENTRY', 'MANUAL_ADJUSTMENT', 'FUNDING',
     'CLIENT_PAYMENT', 'PAYMENT_RETURN', 'COMMISSION_PAYOUT'));
