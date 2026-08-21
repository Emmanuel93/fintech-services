--liquibase formatted sql

--changeset stp-service:005-create-payment-orders
CREATE TABLE stp.payment_orders (
    stp_payment_order_id     UUID          NOT NULL,
    payment_request_id       UUID          NOT NULL,
    company_id               UUID          NOT NULL,
    ordering_account_id      UUID          NOT NULL,
    tracking_key             VARCHAR(30)   NOT NULL,
    business_date            DATE          NOT NULL,
    amount                   NUMERIC(19,2) NOT NULL
        CONSTRAINT payment_orders_amount_pos_chk CHECK (amount > 0),

    -- Se guardan los dos: el completo para auditoría y comparar contra el CEP, y el truncado a 40
    -- que es exactamente lo que entró a la cadena firmada. El legado guardaba sólo el completo y
    -- firmaba el truncado, lo que rompía después la comparación de nombres.
    beneficiary_name         VARCHAR(150)  NOT NULL,
    beneficiary_name_sent    VARCHAR(40)   NOT NULL,

    beneficiary_account      VARCHAR(20)   NOT NULL,
    -- HMAC-SHA256 con salt. El legado usaba SHA-256 pelado sobre un espacio enumerable.
    beneficiary_account_hash VARCHAR(64)   NOT NULL,
    beneficiary_account_type VARCHAR(4)    NOT NULL,
    beneficiary_tax_id       VARCHAR(18),
    beneficiary_institution  INTEGER       NOT NULL,
    concept                  VARCHAR(40),
    numeric_reference        BIGINT,
    payment_type             VARCHAR(4),

    signature                TEXT,
    signing_key_id           UUID,

    status                   VARCHAR(20)   NOT NULL
        CONSTRAINT payment_orders_status_chk
            CHECK (status IN ('PENDING', 'SENT', 'ACCEPTED', 'SETTLED',
                              'REJECTED', 'RETURNED', 'CANCELLED', 'FAILED')),
    stp_order_id             VARCHAR(40),
    banxico_code             INTEGER,
    banxico_reason           VARCHAR(120),
    error_detail             VARCHAR(1000),
    attempt_count            INTEGER       NOT NULL DEFAULT 0,
    last_polled_at           TIMESTAMPTZ,
    cep_url                  VARCHAR(500),
    beneficiary_name_matches BOOLEAN,
    correlation_id           VARCHAR(64),

    created_at               TIMESTAMPTZ   NOT NULL,
    sent_at                  TIMESTAMPTZ,
    settled_at               TIMESTAMPTZ,

    CONSTRAINT payment_orders_pk PRIMARY KEY (stp_payment_order_id),
    -- Idempotencia de entrada: la BD es el árbitro ante carreras entre réplicas.
    CONSTRAINT payment_orders_request_uq  UNIQUE (payment_request_id),
    -- La clave de rastreo es el identificador ante Banxico: no puede repetirse en una empresa.
    CONSTRAINT payment_orders_tracking_uq UNIQUE (company_id, tracking_key)
);

-- Exactamente lo que busca el poller.
CREATE INDEX payment_orders_inflight_idx
    ON stp.payment_orders (company_id, business_date)
    WHERE status IN ('SENT', 'ACCEPTED');

CREATE INDEX payment_orders_company_created_idx
    ON stp.payment_orders (company_id, created_at DESC);
