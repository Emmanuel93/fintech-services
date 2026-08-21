package com.fintech.origination.domain;

import com.fintech.shared.exception.DomainException;

/** El dictamen que se intenta registrar no es válido. */
public class DocumentReviewException extends DomainException {
    public DocumentReviewException(String detail) {
        super("ORIGINATION_INVALID_DOCUMENT_REVIEW", detail);
    }
}
