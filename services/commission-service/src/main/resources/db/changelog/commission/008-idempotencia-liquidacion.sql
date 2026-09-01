--liquibase formatted sql
--changeset commission:008-idempotencia-liquidacion author:system
--comment TK-02: un lote de liquidación por beneficiario y período.

-- `idx_liquidation_batches_beneficiary` es un índice, no una restricción: acelera la consulta y no
-- impide nada. `runLiquidation` lee los ACCRUED, agrupa y crea el lote en pasos separados, así que
-- dos corridas del mismo período —dos réplicas, o un reintento manual— crean dos lotes por el mismo
-- devengo. El resultado es un doble pago a un tercero, que no se corrige con un rollback.
CREATE UNIQUE INDEX uq_liquidation_beneficiary_period
    ON commission.liquidation_batches (beneficiary_party_id, period);
