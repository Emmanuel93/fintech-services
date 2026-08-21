--liquibase formatted sql

--changeset payments-service:002-create-payment-orders
CREATE TABLE payments.payment_orders (
    payment_order_id    UUID            NOT NULL,
    credit_account_id   UUID            NOT NULL,
    obligor_party_id    UUID            NOT NULL,
    amount              NUMERIC(19,2)   NOT NULL
        CONSTRAINT payment_orders_amount_pos_chk CHECK (amount > 0),
    payment_method      VARCHAR(30)     NOT NULL,
    external_ref        VARCHAR(255)    NOT NULL,
    status              VARCHAR(20)     NOT NULL
        CONSTRAINT payment_orders_status_chk
            CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'REVERSED')),
    snapshot_version    BIGINT          NOT NULL DEFAULT 0,
    rejection_reason    VARCHAR(500),
    reversal_reason     VARCHAR(500),
    created_at          TIMESTAMPTZ     NOT NULL,
    confirmed_at        TIMESTAMPTZ,
    rejected_at         TIMESTAMPTZ,
    reversed_at         TIMESTAMPTZ,
    CONSTRAINT payment_orders_pk    PRIMARY KEY (payment_order_id),
    CONSTRAINT payment_orders_ref_uq UNIQUE (external_ref)
);

CREATE INDEX payment_orders_account_created_idx
    ON payments.payment_orders (credit_account_id, created_at DESC);

CREATE INDEX payment_orders_status_idx
    ON payments.payment_orders (status)
    WHERE status = 'PENDING';
