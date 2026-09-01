--liquibase formatted sql
--changeset banking:007-movement-trace
--comment BK-41 · la cadena completa del dinero, consultable en una sola tabla.

-- **Las cuatro preguntas que hoy no se pueden contestar sin abrir cinco servicios:**
--
--   1. Este crédito, ¿por dónde salió su dinero y llegó?
--   2. Esta clave de rastreo, ¿de qué crédito era?
--   3. Este movimiento del banco, ¿a qué corresponde?
--   4. Esta póliza, ¿qué dinero real la respalda?
--
-- La cadena existe —cada eslabón guarda el id del anterior— pero recorrerla exige saltar de cartera
-- a disbursement, de ahí al conector, de ahí al estado de cuenta y de ahí al mayor. Nadie la recorre
-- en una investigación real: se pregunta por chat.
--
-- **Es una proyección, no una fuente de verdad.** Cada eslabón sigue siendo dueño de su dato; esto
-- los enhebra. Reconstruirla desde cero tiene que ser posible, y por eso guarda de qué evento vino
-- cada tramo.
CREATE TABLE banking.movement_trace (
    trace_id          UUID          NOT NULL DEFAULT gen_random_uuid(),

    -- El origen, tal como lo manda quien pide el pago. Opaco para banking.
    source_system     VARCHAR(40),
    source_reference  VARCHAR(120),
    credit_account_id UUID,

    -- Los eslabones del camino.
    payout_id         UUID,
    bank_account_id   UUID,
    tracking_key      VARCHAR(60),
    line_id           UUID,
    voucher_ref       VARCHAR(120),

    amount            NUMERIC(19,4),
    business_date     DATE,
    status            VARCHAR(20)   NOT NULL DEFAULT 'REQUESTED',
    last_event_id     VARCHAR(120),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_movement_trace PRIMARY KEY (trace_id)
);

-- Una traza por pago. `payout_id` es la única llave presente en todos los tramos del camino.
CREATE UNIQUE INDEX uq_movement_trace_payout
    ON banking.movement_trace (payout_id) WHERE payout_id IS NOT NULL;

-- Las cuatro preguntas, un índice cada una.
CREATE INDEX idx_movement_trace_cuenta   ON banking.movement_trace (credit_account_id);
CREATE INDEX idx_movement_trace_clave    ON banking.movement_trace (tracking_key);
CREATE INDEX idx_movement_trace_linea    ON banking.movement_trace (line_id);
CREATE INDEX idx_movement_trace_voucher  ON banking.movement_trace (voucher_ref);
