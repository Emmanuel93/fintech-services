--liquibase formatted sql

--changeset stp-service:006-create-payment-order-events
-- Bitácora append-only. Traza regulatoria: nunca se actualiza ni se borra.
CREATE TABLE stp.payment_order_events (
    event_id             UUID          NOT NULL,
    stp_payment_order_id UUID          NOT NULL,
    from_status          VARCHAR(20),
    to_status            VARCHAR(20)   NOT NULL,
    reason_code          VARCHAR(60),
    detail               VARCHAR(1000),
    actor                VARCHAR(60)   NOT NULL,
    occurred_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT payment_order_events_pk PRIMARY KEY (event_id)
);

CREATE INDEX payment_order_events_order_idx
    ON stp.payment_order_events (stp_payment_order_id, occurred_at);
