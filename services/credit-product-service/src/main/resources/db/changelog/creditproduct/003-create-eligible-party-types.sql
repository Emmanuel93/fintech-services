CREATE TABLE credit_product.credit_product_eligible_party_types
(
    product_definition_id UUID        NOT NULL,
    party_type            VARCHAR(20) NOT NULL,

    CONSTRAINT pk_eligible_party_types
        PRIMARY KEY (product_definition_id, party_type),
    CONSTRAINT fk_ept_definition
        FOREIGN KEY (product_definition_id)
        REFERENCES credit_product.credit_product_definitions (product_definition_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_ept_party_type
        CHECK (party_type IN ('INDIVIDUAL','BUSINESS','DISTRIBUTOR','GUARANTOR','BENEFICIARY'))
);
