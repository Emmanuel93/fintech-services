--liquibase formatted sql
--changeset banking:006-internal-movements
--comment BK-38 · lo que la plataforma dice que pasó, proyectado aquí para poder cruzarlo.

-- **Por qué una proyección y no una consulta al vecino.** Conciliar es <barrer> un día entero de
-- movimientos, no preguntar de uno en uno: una consulta por línea contra `stp` o `disbursement`
-- convertiría cada cierre bancario en cientos de llamadas, y ataría la conciliación —que es un
-- proceso por lotes, tolerante a que el vecino esté caído— a la disponibilidad de otro servicio.
--
-- Se alimenta de eventos. Banking no conoce el dominio de crédito: recibe hechos con su referencia
-- opaca, su importe, su fecha y su clave de rastreo, y eso es todo lo que el cruce necesita.
CREATE TABLE banking.internal_movements (
    internal_movement_id UUID          NOT NULL DEFAULT gen_random_uuid(),
    movement_type        VARCHAR(24)   NOT NULL,
    reference            VARCHAR(120)  NOT NULL,
    amount               NUMERIC(19,4) NOT NULL,
    business_date        DATE          NOT NULL,
    tracking_key         VARCHAR(60),
    direction            VARCHAR(6)    NOT NULL,
    source_event_id      VARCHAR(120)  NOT NULL,
    projected_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_internal_movements PRIMARY KEY (internal_movement_id),
    -- Idempotencia de la proyección: el mismo evento reentregado no duplica el candidato. Sin esto,
    -- un reintento de Kafka volvería ambiguo un cruce que era determinista.
    CONSTRAINT uq_internal_movement_event UNIQUE (source_event_id),
    CONSTRAINT chk_internal_movement_type CHECK (movement_type IN
        ('PAYMENT_ORDER','DISBURSEMENT_ORDER','STP_ORDER','JOURNAL_ENTRY','MANUAL_ADJUSTMENT')),
    CONSTRAINT chk_internal_movement_direction CHECK (direction IN ('CREDIT','DEBIT')),
    CONSTRAINT chk_internal_movement_amount CHECK (amount > 0)
);

-- El cruce determinista de la primera pasada.
CREATE UNIQUE INDEX uq_internal_movement_tracking
    ON banking.internal_movements (tracking_key)
    WHERE tracking_key IS NOT NULL;

-- El de la segunda: importe y fecha.
CREATE INDEX idx_internal_movements_importe_fecha
    ON banking.internal_movements (business_date, amount);
