--liquibase formatted sql
--changeset banking-service:012-traza-fallida
--comment A payout trace ends FAILED, with its reason, when the payout ended without the money leaving (D61): before, nothing told the trace, and a rejected payout stayed dispatched for good.

ALTER TABLE banking.movement_trace ADD COLUMN failure_reason VARCHAR(500);
