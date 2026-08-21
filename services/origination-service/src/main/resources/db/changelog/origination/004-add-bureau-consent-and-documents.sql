-- D3 Origination — prospect_documents table
CREATE TABLE origination.prospect_documents (
    prospect_id        UUID         NOT NULL,
    document_type      VARCHAR(30)  NOT NULL,
    document_ref       VARCHAR(500) NOT NULL,
    income_proof_type  VARCHAR(30),
    uploaded_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT fk_prospect_documents_prospect
        FOREIGN KEY (prospect_id) REFERENCES origination.prospects(prospect_id) ON DELETE CASCADE,
    CONSTRAINT ck_prospect_doc_type
        -- SELFIE es prueba de vida: la app la pide entre los cuatro documentos obligatorios del
        -- alta y forma parte del expediente de identificación, no es evidencia auxiliar.
        CHECK (document_type IN ('INE_FRONT', 'INE_BACK', 'SELFIE', 'ADDRESS_PROOF', 'INCOME_PROOF')),
    CONSTRAINT ck_income_proof_type
        CHECK (income_proof_type IS NULL OR income_proof_type IN ('PAYROLL', 'BANK_STATEMENT', 'TAX_RETURN', 'BUSINESS_ACTIVITY')),
    CONSTRAINT ck_income_proof_type_scope
        CHECK (income_proof_type IS NULL OR document_type = 'INCOME_PROOF')
);

CREATE INDEX idx_prospect_documents_prospect_id ON origination.prospect_documents (prospect_id);
