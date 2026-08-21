--liquibase formatted sql

--changeset charges-service:003-create-charge-records
CREATE TABLE charges.charge_records (
    charge_id           UUID        NOT NULL,
    credit_account_id   UUID        NOT NULL,
    obligor_party_id    UUID        NOT NULL,
    charge_type         VARCHAR(50) NOT NULL,
    status              VARCHAR(20) NOT NULL
        CONSTRAINT charge_records_status_chk CHECK (status IN ('APPLIED', 'REVERSED', 'WAIVED')),
    basis               NUMERIC(19,2),
    rate                NUMERIC(12,8),
    days                INTEGER,
    amount              NUMERIC(19,2) NOT NULL
        CONSTRAINT charge_records_amount_pos_chk CHECK (amount > 0),
    tax_amount          NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_amount        NUMERIC(19,2) NOT NULL,
    accrual_date        DATE        NOT NULL,
    linked_charge_id    UUID,
    reversal_reason     VARCHAR(500),
    waived_by           VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL,
    reversed_at         TIMESTAMPTZ,
    CONSTRAINT charge_records_pk            PRIMARY KEY (charge_id),
    CONSTRAINT charge_records_linked_fk     FOREIGN KEY (linked_charge_id)
        REFERENCES charges.charge_records (charge_id)
);

CREATE INDEX charge_records_account_date_idx
    ON charges.charge_records (credit_account_id, accrual_date DESC);

CREATE INDEX charge_records_type_account_idx
    ON charges.charge_records (charge_type, credit_account_id);

CREATE INDEX charge_records_linked_idx
    ON charges.charge_records (linked_charge_id)
    WHERE linked_charge_id IS NOT NULL;
