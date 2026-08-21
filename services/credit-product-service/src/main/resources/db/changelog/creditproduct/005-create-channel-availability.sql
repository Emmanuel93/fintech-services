CREATE TABLE credit_product.credit_product_channel_availability
(
    product_definition_id UUID        NOT NULL,
    channel_type          VARCHAR(30) NOT NULL,

    CONSTRAINT pk_channel_availability
        PRIMARY KEY (product_definition_id, channel_type),
    CONSTRAINT fk_ca_definition
        FOREIGN KEY (product_definition_id)
        REFERENCES credit_product.credit_product_definitions (product_definition_id)
        ON DELETE CASCADE
);
