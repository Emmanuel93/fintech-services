package com.fintech.origination.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;

@Embeddable
public class ProspectDocument {

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private ProspectDocumentType documentType;

    @Column(name = "document_ref", nullable = false, length = 500)
    private String documentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "income_proof_type", length = 30)
    private IncomeProofType incomeProofType;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected ProspectDocument() {}

    public ProspectDocument(ProspectDocumentType documentType, String documentRef) {
        this(documentType, documentRef, null);
    }

    public ProspectDocument(ProspectDocumentType documentType, String documentRef,
                            IncomeProofType incomeProofType) {
        this.documentType    = documentType;
        this.documentRef     = documentRef;
        this.incomeProofType = incomeProofType;
        this.uploadedAt      = Instant.now();
    }

    public ProspectDocumentType getDocumentType()  { return documentType; }
    public String getDocumentRef()                 { return documentRef; }
    public IncomeProofType getIncomeProofType()    { return incomeProofType; }
    public Instant getUploadedAt()                 { return uploadedAt; }
}
