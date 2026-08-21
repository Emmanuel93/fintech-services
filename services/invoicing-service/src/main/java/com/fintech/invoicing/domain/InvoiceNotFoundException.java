package com.fintech.invoicing.domain;

import com.fintech.shared.exception.DomainException;

public class InvoiceNotFoundException extends DomainException {
    public InvoiceNotFoundException(String id) {
        super("INVOICING_INVOICE_NOT_FOUND", "Invoice not found: " + id);
    }
}
