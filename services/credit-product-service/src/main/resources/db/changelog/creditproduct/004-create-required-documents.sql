CREATE TABLE credit_product.credit_product_required_documents
(
    product_definition_id UUID        NOT NULL,
    document_type         VARCHAR(50) NOT NULL,
    mandatory             BOOLEAN     NOT NULL DEFAULT TRUE,

    CONSTRAINT pk_required_documents
        PRIMARY KEY (product_definition_id, document_type),
    CONSTRAINT fk_rd_definition
        FOREIGN KEY (product_definition_id)
        REFERENCES credit_product.credit_product_definitions (product_definition_id)
        ON DELETE CASCADE
);
