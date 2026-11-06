--liquibase formatted sql
--changeset wallet:009-estados-de-cartera
--comment The wallet takes every status the portfolio publishes: a cancelled credit (D63) was refused and its balance went to the DLT.

-- The view copies the account's status from balance-updated as it comes. Its check knew six statuses and the portfolio
-- has more: a credit cancelled because its only disbursement failed (D63), a closed, a restructured or a not yet active
-- one. A status it did not know made the update fail, retry and go to the DLT, holding the topic back meanwhile.
ALTER TABLE wallet.wallet_views DROP CONSTRAINT chk_wallet_views_status;
ALTER TABLE wallet.wallet_views ADD CONSTRAINT chk_wallet_views_status CHECK (status IN (
    'PENDING_ACTIVATION','ACTIVE','SUSPENDED','RESTRUCTURED','DELINQUENT','SETTLED','WRITTEN_OFF','CLOSED',
    'CANCELLED','UNKNOWN'
));
