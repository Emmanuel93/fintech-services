--liquibase formatted sql
--changeset channels:004-create-customer-intents

CREATE TABLE channels.customer_intents (
    intent_id         UUID           NOT NULL PRIMARY KEY,
    session_id        UUID           NOT NULL REFERENCES channels.sessions (session_id),
    intent_type       VARCHAR(50)    NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    routed_to         VARCHAR(50),
    handoff_event_id  UUID,
    product_type_hint VARCHAR(100),
    requested_amount  NUMERIC(15, 2),
    promoter_code     VARCHAR(100),
    abandon_reason    VARCHAR(200),
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_intents_session_id ON channels.customer_intents (session_id);
CREATE INDEX idx_intents_status ON channels.customer_intents (status);
