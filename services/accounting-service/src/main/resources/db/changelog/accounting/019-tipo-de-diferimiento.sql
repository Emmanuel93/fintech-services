--liquibase formatted sql
--changeset accounting:019-tipo-de-diferimiento
--comment The kind of a deferral fits 'FUNDING'.

-- 017 made the column four characters wide ('FEE', 'COST'); 018 allowed 'FUNDING' in the check but not in the
-- column, and a funding line's opening fee could not be deferred: the event went to the DLT and 2209 never moved
-- (E2E 3 Nov, fee of 10,000).
ALTER TABLE accounting.deferred_fees ALTER COLUMN kind TYPE VARCHAR(12);
