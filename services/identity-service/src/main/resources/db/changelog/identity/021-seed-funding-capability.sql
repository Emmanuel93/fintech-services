-- liquibase formatted sql

-- changeset identity:021 author:fintech
-- Funding: recording where the money the institution lends comes from — a capital contribution, a funding line,
-- its drawdowns, interest and repayments. It is bookkeeping of treasury, not a payment: the money moves at the bank
-- and this records it. Treasury (FINANCE) and the administrator record it; the operations supervisor and the auditor
-- read it with treasury.view.
INSERT INTO identity.role_capabilities (role_code, capability, granted_by) VALUES
    ('ADMIN',   'treasury.funding', 'seed'),
    ('FINANCE', 'treasury.funding', 'seed')
ON CONFLICT (role_code, capability) DO NOTHING;
