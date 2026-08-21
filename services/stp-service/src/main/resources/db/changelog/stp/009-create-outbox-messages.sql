--liquibase formatted sql

--changeset stp-service:009-create-outbox-messages
-- Outbox transaccional: garantiza que NUNCA se llame a STP dentro de la transacción que persiste
-- la orden. El legado tapaba la misma carrera publicando todo con dos minutos de retardo.
CREATE TABLE stp.outbox_messages (
    outbox_id       UUID         NOT NULL,
    message_type    VARCHAR(40)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL
        CONSTRAINT outbox_messages_status_chk
            CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SENT', 'FAILED')),
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT outbox_messages_pk PRIMARY KEY (outbox_id)
);

-- Índice del SELECT ... FOR UPDATE SKIP LOCKED del relay.
CREATE INDEX outbox_messages_pending_idx
    ON stp.outbox_messages (next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX outbox_messages_aggregate_idx
    ON stp.outbox_messages (aggregate_id);
