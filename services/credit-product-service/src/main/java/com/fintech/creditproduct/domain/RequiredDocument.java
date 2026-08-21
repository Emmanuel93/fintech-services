package com.fintech.creditproduct.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

@Embeddable
public class RequiredDocument {

    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    @Column(name = "mandatory", nullable = false)
    private boolean mandatory;

    protected RequiredDocument() {}

    public RequiredDocument(String documentType, boolean mandatory) {
        this.documentType = documentType;
        this.mandatory = mandatory;
    }

    public String getDocumentType() { return documentType; }
    public boolean isMandatory()    { return mandatory; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RequiredDocument that)) return false;
        return Objects.equals(documentType, that.documentType);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(documentType);
    }
}
