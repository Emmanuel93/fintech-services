package com.fintech.invoicing.application.port.out;

import com.fintech.invoicing.domain.Invoice;

public interface InvoicingEventPublisher {
    void publishInvoiceGenerated(Invoice invoice);
}
