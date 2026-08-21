--liquibase formatted sql

--changeset disbursement-service:005-create-disbursement-events
-- DB-06: bitácora inmutable. En el legado el estado de una dispersión sólo se podía reconstruir
-- leyendo logs; aquí cada transición deja evidencia consultable.
CREATE TABLE disbursement.disbursement_events (
    event_id        UUID         NOT NULL,
    disbursement_id UUID         NOT NULL,
    company_id      UUID         NOT NULL,
    from_status     VARCHAR(20),
    to_status       VARCHAR(20)  NOT NULL,
    reason_code     VARCHAR(60),
    detail          VARCHAR(1000),
    actor           VARCHAR(60)  NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT disbursement_events_pk PRIMARY KEY (event_id),
    CONSTRAINT disbursement_events_order_fk
        FOREIGN KEY (disbursement_id)
        REFERENCES disbursement.disbursement_orders (disbursement_id)
);

CREATE INDEX disbursement_events_order_idx
    ON disbursement.disbursement_events (disbursement_id, occurred_at);
