CREATE TABLE credit_product.credit_product_payment_frequencies
(
    product_definition_id UUID        NOT NULL,
    payment_frequency     VARCHAR(20) NOT NULL,

    CONSTRAINT pk_credit_product_payment_frequencies
        PRIMARY KEY (product_definition_id, payment_frequency),
    CONSTRAINT fk_payment_freq_product
        FOREIGN KEY (product_definition_id)
        REFERENCES credit_product.credit_product_definitions (product_definition_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_payment_frequency_val
        CHECK (payment_frequency IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY'))
);
