--liquibase formatted sql
--changeset wallet:008-disposicion-una-vez
--comment A disposition is credited to the wallet once: a redelivered disposition-completed added the money again.

-- The movement of a credited disposition carries the disposition id as reference; one per disposition. The balance
-- is incremented only after this row is in, so a redelivery (or two pods consuming at once) cannot credit twice.
CREATE UNIQUE INDEX IF NOT EXISTS uq_wallet_movements_disposition
    ON wallet.wallet_movements (reference) WHERE type = 'DISPOSITION' AND reference IS NOT NULL;
