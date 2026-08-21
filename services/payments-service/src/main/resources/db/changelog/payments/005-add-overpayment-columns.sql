--liquibase formatted sql

--changeset payments-service:005-add-overpayment-columns
ALTER TABLE payments.payment_orders
    ADD COLUMN requested_amount    NUMERIC(19,2),
    ADD COLUMN overpayment_strategy VARCHAR(30);
