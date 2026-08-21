package com.fintech.creditproduct.infrastructure.adapter.in.api.dto;

import com.fintech.creditproduct.domain.RequiredDocument;

public record RequiredDocumentResponse(String documentType, boolean mandatory) {

    public static RequiredDocumentResponse from(RequiredDocument d) {
        return new RequiredDocumentResponse(d.getDocumentType(), d.isMandatory());
    }
}
