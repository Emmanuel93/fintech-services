--liquibase formatted sql
--changeset creditportfolio:020-skip-payment
--comment BK-30 · saltar un pago: la cuota se corre y NO genera mora.

-- **Cero referencias en todo el monorepo** antes de esto: ni la palabra, ni el concepto.
--
-- Saltar un pago **congela la deuda**, y qué significa congelarla lo decide el producto:
--
--   · GIFT     — el período saltado NO devenga. Es una recompensa real.
--   · DEFERRAL — sigue devengando; sólo se corre el compromiso.
--
-- En los dos casos el vencimiento se mueve y la cuota **deja de ser exigible** en su fecha
-- original: sin eso, saltar un pago produciría exactamente la mora que pretende evitar.
ALTER TABLE credit_portfolio.installments
    ADD COLUMN skipped_at        TIMESTAMPTZ,
    ADD COLUMN skipped_mode      VARCHAR(12)
        CONSTRAINT installments_skip_mode_chk CHECK (skipped_mode IN ('GIFT', 'DEFERRAL')),
    ADD COLUMN original_due_date DATE;

-- `original_due_date` no es cosmético: es lo que permite explicarle al cliente —y a una revisión—
-- de qué fecha a qué fecha se movió su compromiso y por qué.
COMMENT ON COLUMN credit_portfolio.installments.original_due_date IS
    'El vencimiento que tenía antes de saltarse o de un programa de apoyo. NULL = nunca se movió.';

-- El tope por ciclo se cuenta sobre esto, así que la consulta tiene que ser barata.
CREATE INDEX idx_installments_saltadas
    ON credit_portfolio.installments (schedule_id, skipped_at)
    WHERE skipped_at IS NOT NULL;
